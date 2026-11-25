package com.aichat.app.core.audio

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edge TTS 客户端：把一段文本合成成 MP3 文件。
 *
 * 【协议流程】（2026-10 版，旧版会被 403）
 * 1. WebSocket 连到 speech.platform.bing.com，URL 带 TrustedClientToken +
 *    ConnectionId + Sec-MS-GEC 签名（签名算法见 SecMsGec）
 * 2. 先发一条 speech.config（声明输出格式与音频参数）
 * 3. 再发一条 SSML（带 X-RequestId）
 * 4. 服务端回若干二进制帧，每帧开头 2 字节大端是头部长度，头部之后才是 MP3 数据
 * 5. 收到 `Path:turn.end` 的文本帧表示合成结束
 *
 * 【为什么二进制帧要剥头】
 * 那些帧不是裸 MP3，前面挂着一个文本头（形如
 * `X-RequestId:...\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n`）。
 * 不剥头直接写文件，播放器会把它当损坏的 MP3（能识别时长但播放爆音）。
 */
class EdgeTtsClient(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val TAG = "EdgeTts"
        private const val CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

        /**
         * 微软会校验 Chrome 版本是否够新，130 实测被 403。
         * 143 是当前验证可用的版本，跟着 rany2/edge-tts 走。
         */
        private const val CHROME_VERSION = "143.0.3650.75"

        private const val WSS_HOST = "speech.platform.bing.com"
        private const val WSS_PATH = "/consumer/speech/synthesize/readaloud/edge/v1"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 合成一段文本。
     *
     * @param text 要朗读的文本（已清掉 markdown）
     * @param outFile 输出 mp3 路径
     * @param voice 语音名，如 zh-CN-XiaoxiaoNeural
     * @param rate 语速，形如 "+0%" / "-10%"
     * @param pitch 音调，形如 "+0Hz"
     * @return 成功返回 true
     */
    fun synthesize(
        text: String,
        outFile: File,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: String = "+0%",
        pitch: String = "+0Hz",
        timeoutSec: Long = 60,
    ): Boolean {
        if (text.isBlank()) return false

        val reqId = UUID.randomUUID().toString().replace("-", "")
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val mp3 = java.io.ByteArrayOutputStream()

        val url = buildUrl()
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1) 配置帧
                val ts = java.text.SimpleDateFormat(
                    "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                    java.util.Locale.US,
                ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

                val config = "X-Timestamp:$ts\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

                webSocket.send(config)
                webSocket.send(buildSsml(reqId, text, voice, rate, pitch, ts))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制帧：前 2 字节大端 = 头部长度，之后是头部文本，再之后是音频
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (data.size < 2 + headerLen) return
                val header = String(data, 2, headerLen, Charsets.UTF_8)

                if (header.contains("Path:audio", ignoreCase = true)) {
                    // 音频数据从 2+headerLen 开始
                    if (data.size > 2 + headerLen) {
                        synchronized(mp3) {
                            mp3.write(data, 2 + headerLen, data.size - 2 - headerLen)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end", ignoreCase = true)) {
                    ok.set(true)
                    done.countDown()
                    webSocket.close(1000, null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message} (HTTP ${response?.code})", t)
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val ws = httpClient.newWebSocket(request, listener)
        val finished = done.await(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            Log.e(TAG, "合成超时（${timeoutSec}s）")
            ws.cancel()
            return false
        }

        if (!ok.get()) return false

        val audio = synchronized(mp3) { mp3.toByteArray() }
        if (audio.isEmpty()) {
            Log.e(TAG, "合成结果为空")
            return false
        }

        return try {
            outFile.writeBytes(audio)
            true
        } catch (e: Exception) {
            Log.e(TAG, "写 mp3 失败", e)
            false
        }
    }

    private fun buildUrl(): String {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val gec = SecMsGec.generate()
        return "wss://$WSS_HOST$WSS_PATH" +
            "?TrustedClientToken=$CLIENT_TOKEN" +
            "&ConnectionId=$connId" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=2-$CHROME_VERSION"
    }

    private fun buildSsml(
        reqId: String,
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        ts: String,
    ): String {
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'>" +
            "<prosody rate='$rate' pitch='$pitch'>${escapeXml(text)}</prosody>" +
            "</voice></speak>"

        return "X-RequestId:$reqId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${ts}Z\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
