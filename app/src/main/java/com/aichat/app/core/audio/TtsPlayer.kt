package com.aichat.app.core.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式 TTS 播放器：边生成边合成边播放。
 *
 * 【为什么合成和播放要分两条链路】
 * 合成是网络 IO（每段几百毫秒），播放是实时消耗（每段几秒）。放在同一条协程里
 * 会互相阻塞 —— 播放时不能合成，合成时没声音。所以：
 *   合成链路：文本流入 → 分段 → 逐段合成 mp3 → 投进队列
 *   播放链路：从队列取 mp3 → 播完 → 取下一段
 * 队列容量 2 够「正在播 1 段 + 已备好 1 段」，再多只是堆内存。
 *
 * 【为什么要预取】
 * 段间 MediaPlayer 有 prepare 开销（几十到几百毫秒）。等播完再合成下一段，
 * 每段之间都有明显空档。提前备好一段，播完立刻接上，听感才连贯。
 *
 * 【打断语义】
 * 用户发新消息 / 关 TTS 必须立刻停。用 generation 计数器：每次 stop 递增，
 * 异步任务回来发现 generation 变了就丢弃 —— 避免「已经停了，迟到的音频突然响起来」。
 */
class TtsPlayer(private val cacheDir: File) {

    companion object {
        private const val TAG = "TtsPlayer"
        private const val QUEUE_CAPACITY = 2
    }

    private val client = EdgeTtsClient()
    private var scope: CoroutineScope? = null
    private var mediaPlayer: MediaPlayer? = null
    private val playing = AtomicBoolean(false)

    @Volatile
    private var generation = 0

    /** 流式朗读时接收文本增量的通道。null 表示当前不在朗读。 */
    @Volatile
    private var feedChannel: Channel<String>? = null

    val isPlaying: Boolean get() = playing.get()

    /**
     * 开始流式朗读。之后每段文本用 [feed] 送入，全部送完后调 [finish]。
     *
     * 拆成 start/feed/finish 而不是传 lambda：调用方在流式回调（可能在别的线程）
     * 里拿到增量，直接 feed 即可，不用把整条回调链改成 suspend。
     */
    fun startStream(voice: String) {
        stop()
        val gen = generation
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        playing.set(true)

        val input = Channel<String>(Channel.UNLIMITED)
        feedChannel = input

        s.launch {
            val audioQueue = Channel<File>(QUEUE_CAPACITY)
            var buffer = ""
            var isFirst = true
            var idx = 0

            // —— 合成链路 ——
            val producer = launch {
                suspend fun emit(parts: List<String>) {
                    for (part in parts) {
                        if (generation != gen) return
                        val f = File(cacheDir, "tts_${gen}_${idx++}.mp3")
                        if (client.synthesize(part, f, voice = voice)) {
                            audioQueue.send(f)
                            isFirst = false
                        }
                    }
                }

                try {
                    for (delta in input) {
                        if (generation != gen) break
                        buffer += delta
                        val (parts, rest) = TtsSegmenter.take(buffer, flush = false, isFirst = isFirst)
                        buffer = rest
                        emit(parts)
                    }
                    // 输入结束 —— 把剩余缓冲全部吐出来
                    val (tail, _) = TtsSegmenter.take(buffer, flush = true, isFirst = isFirst)
                    emit(tail)
                } catch (e: Exception) {
                    Log.e(TAG, "合成链路异常", e)
                } finally {
                    audioQueue.close()
                }
            }

            // —— 播放链路 ——
            try {
                for (file in audioQueue) {
                    if (generation != gen) {
                        file.delete()
                        break
                    }
                    playFile(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "播放链路异常", e)
            } finally {
                producer.cancel()
                if (generation == gen) {
                    playing.set(false)
                    feedChannel = null
                    cleanupCache(gen)
                }
            }
        }
    }

    /** 送入一段文本增量（流式回调里直接调）。 */
    fun feed(delta: String) {
        if (delta.isEmpty()) return
        val ch = feedChannel ?: return
        ch.trySend(delta)
    }

    /** 告诉播放器文本已经送完，可以收尾了。 */
    fun finish() {
        feedChannel?.close()
        feedChannel = null
    }

    /** 一次性朗读整段文本（非流式场景，如重播某条回复）。 */
    fun speak(text: String, voice: String) {
        startStream(voice)
        feed(TtsSegmenter.clean(text))
        finish()
    }

    private suspend fun playFile(file: File) = withContext(Dispatchers.IO) {
        val latch = CountDownLatch(1)
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { latch.countDown() }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer 错误 what=$what extra=$extra")
                latch.countDown()
                true
            }
            synchronized(this@TtsPlayer) { mediaPlayer = player }
            player.prepareAsync()
            latch.await(120, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "播放失败: ${file.name}", e)
        } finally {
            try {
                player.release()
            } catch (_: Exception) {
            }
            synchronized(this@TtsPlayer) {
                if (mediaPlayer === player) mediaPlayer = null
            }
            file.delete()
        }
    }

    /** 立刻停止朗读，让在途合成结果作废。 */
    fun stop() {
        generation++
        playing.set(false)
        feedChannel?.close()
        feedChannel = null
        synchronized(this) {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            mediaPlayer = null
        }
        scope?.cancel()
        scope = null
    }

    private fun cleanupCache(gen: Int) {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("tts_${gen}_")) f.delete()
            }
        } catch (_: Exception) {
        }
    }
}
