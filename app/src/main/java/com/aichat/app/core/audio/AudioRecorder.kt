package com.aichat.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * 录音器：输出 ASR 要求的 WAV（单声道 / 16-bit PCM / 16kHz）。
 *
 * 【为什么不用 MediaRecorder】
 * MediaRecorder 输出的是容器格式（m4a/aac），要么给 ASR 传容器、要么自己解封装；
 * 而且它 start()/stop() 有明显延迟（几百毫秒），长按松手后还要等它收尾。
 * chat-demo 的 Web 实现也是「拿原始 PCM 自己拼 WAV 头」：
 * 采集和编码都在本地完成，上传前不需要转码，松手即可上传。
 *
 * 【为什么必须 16kHz 单声道 16-bit】
 * ASR 接口固定接受这个格式。采样率不对会导致识别结果乱码或直接报格式错误。
 *
 * 【线程模型】
 * AudioRecord.read 是阻塞调用，必须在独立线程里循环读。
 * stop() 只置标志位 + join，不直接调用 audioRecord.stop()（避免和读线程竞争导致
 * IllegalStateException）。
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** ASR 单次时长上限 120 秒（接口硬限制，超了会被拒）。 */
        const val MAX_DURATION_MS = 120_000L

        /** PCM 缓冲初始容量：16kHz * 2 字节 * 8 秒 ≈ 256KB，常见短语音不用扩容。 */
        private const val INITIAL_CAPACITY = 16000 * 2 * 8
    }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    private var recording = false

    @Volatile
    private var stopRequested = false

    /**
     * PCM 累积缓冲。
     *
     * 【为什么不用 ByteArrayOutputStream】
     * 它内部是「数组 + 每次扩容 copyOf」，而且最后 toByteArray() 还要再整份拷一次。
     * 6 秒录音约 190KB 不痛不痒，但 120 秒上限是 3.8MB —— 收尾时要多拷两遍，
     * 松手到出字的延迟里有一部分就耗在这。
     *
     * 改成「预估容量一次性分配 + write 时逐段写入」：
     * 120 秒的容量按需增长（每次翻倍），最终写出文件时只需一次 copyInto。
     */
    private val lock = Any()
    private var pcmData = ByteArray(INITIAL_CAPACITY)
    private var pcmLength = 0

    /** 已录制时长（毫秒），供 UI 显示读秒。 */
    @Volatile
    var recordedMs: Long = 0
        private set

    val isRecording: Boolean get() = recording

    /**
     * 开始录音。返回 false 表示启动失败（无权限 / 麦克风被占用 / 设备不支持）。
     *
     * 设备不支持 16kHz 时会抛 IllegalArgumentException —— 这种情况在本机不会出现，
     * 但有些模拟器会，所以兜底返回 false 而不是崩溃。
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize 返回 $minBuf，设备不支持 16kHz 单声道")
            return false
        }

        // 【2026-11-25 改】原来是 minBuf * 4。
        //
        // AudioRecord.read 是阻塞的：松手时读线程可能正卡在一次 read 里，
        // 要等这个缓冲区被填满才返回。缓冲区越大，join 最长等待越久 ——
        // 4 倍时理论最坏要等约 0.5 秒，体感就是「松手后卡一下才开始识别」。
        //
        // 改成 minBuf（且不小于 2048 字节，约 64ms）：
        // 保证不欠采样（低于 minBuf 会丢帧爆音），同时把 join 的最长等待压到
        // 几十毫秒级。read 调用频率略高，但对 16kHz 单声道来说 CPU 开销可忽略。
        val bufSize = maxOf(minBuf, 2048)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建 AudioRecord 失败", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化")
            record.release()
            return false
        }

        synchronized(lock) { pcmLength = 0 }
        recordedMs = 0
        stopRequested = false
        recording = true
        audioRecord = record

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "startRecording 后状态不是 RECORDING")
            recording = false
            record.release()
            audioRecord = null
            return false
        }

        recordThread = thread(name = "AudioRecorder", priority = Thread.MAX_PRIORITY) {
            val buf = ShortArray(bufSize / 2)
            var totalSamples = 0
            while (!stopRequested) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    // ShortArray → 小端字节流。WAV 要求小端。
                    val bytes = ByteArray(read * 2)
                    var i = 0
                    while (i < read) {
                        val s = buf[i].toInt()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        i++
                    }
                    synchronized(lock) {
                        if (pcmLength + bytes.size > pcmData.size) {
                            // 翻倍扩容，够 120 秒上限时最多扩 10 次
                            var cap = pcmData.size
                            while (cap < pcmLength + bytes.size) cap *= 2
                            pcmData = pcmData.copyOf(cap)
                        }
                        bytes.copyInto(pcmData, pcmLength)
                        pcmLength += bytes.size
                    }
                    totalSamples += read
                    recordedMs = totalSamples * 1000L / SAMPLE_RATE

                    // 到达接口上限就自动停，避免录出一个必然被拒的文件
                    if (recordedMs >= MAX_DURATION_MS) {
                        stopRequested = true
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read 返回 $read")
                    break
                }
            }
        }

        return true
    }

    /**
     * 停止录音并返回 WAV 文件。没有采到任何样本时返回 null。
     *
     * 必须 join 读线程后再取数据 —— 否则最后一个缓冲区还在写，会截断尾部。
     */
    fun stop(cacheDir: File): File? {
        if (!recording) return null
        stopRequested = true
        recording = false

        recordThread?.let { t ->
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        recordThread = null

        audioRecord?.let { r ->
            try {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
            } catch (_: Exception) {
            }
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        audioRecord = null

        val (pcm, len) = synchronized(lock) {
            val snapshot = pcmData
            val n = pcmLength
            pcmLength = 0
            snapshot to n
        }

        // 空录音（按一下就松）不上传 —— 服务端只会返回空串，还浪费一次请求
        if (len == 0) return null

        val out = File(cacheDir, "voice_${System.currentTimeMillis()}.wav")
        return try {
            // 直接按最终长度拼一次，不再有中间拷贝
            out.writeBytes(buildWav(pcm, len))
            out
        } catch (e: Exception) {
            Log.e(TAG, "写 WAV 失败", e)
            null
        }
    }

    /** 放弃录音，不产出文件。 */
    fun cancel() {
        stopRequested = true
        recording = false
        recordThread?.let {
            try {
                it.join(1000)
            } catch (_: InterruptedException) {
            }
        }
        recordThread = null
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        audioRecord = null
        synchronized(lock) { pcmLength = 0 }
        recordedMs = 0
    }

    /**
     * 拼 44 字节 WAV 头 + PCM 数据。
     *
     * 字段顺序与 chat-demo 的 encodeWav 完全一致：ASR 侧按这个头解析，
     * 少一个字段或字节序写反都会导致「文件能播放但识别失败」这种难查的问题。
     */
    private fun buildWav(pcm: ByteArray, pcmSize: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmSize
        val out = ByteArray(44 + dataSize)

        fun putAscii(offset: Int, s: String) {
            for (i in s.indices) out[offset + i] = s[i].code.toByte()
        }
        fun putInt32LE(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
            out[offset + 2] = ((v shr 16) and 0xFF).toByte()
            out[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putInt16LE(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putInt32LE(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt32LE(16, 16)          // fmt chunk 长度
        putInt16LE(20, 1)           // PCM
        putInt16LE(22, channels)
        putInt32LE(24, SAMPLE_RATE)
        putInt32LE(28, byteRate)
        putInt16LE(32, blockAlign)
        putInt16LE(34, bitsPerSample)
        putAscii(36, "data")
        putInt32LE(40, dataSize)
        pcm.copyInto(out, 44, 0, dataSize)

        return out
    }
}
