package com.aichat.app.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 语音识别客户端。
 *
 * 接口形状与 deepseek chat-demo 一致：
 *   POST {base}{path}，multipart 上传 file=WAV，返回 {"text": "..."}
 *
 * WAV 必须是 16kHz 单声道 16-bit PCM（AudioRecorder 产出的就是这个）。
 */
class AsrClient(
    private val baseUrl: String = Config.ASR_BASE_URL,
    private val path: String = Config.ASR_PATH,
    private val apiKey: String = Config.ASR_API_KEY,
) {

    companion object {
        private const val TAG = "AsrClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 上传 + 识别：大文件（接近 120 秒上限时约 3.8MB）传上去还要等服务端转写
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class AsrResponse(val text: String? = null)

    /**
     * 识别一个 WAV 文件。
     *
     * @return 识别出的文本；失败返回 null（错误信息写日志，UI 侧只提示"没听清"）
     */
    suspend fun transcribe(wav: File): String? = withContext(Dispatchers.IO) {
        if (!wav.exists() || wav.length() == 0L) {
            Log.w(TAG, "音频文件为空")
            return@withContext null
        }
        if (apiKey.isBlank() || apiKey.startsWith("sk-REPLACE")) {
            Log.w(TAG, "ASR key 未配置")
            return@withContext null
        }

        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "recording.wav",
                    wav.asRequestBody("audio/wav".toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}${path}")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "识别失败 HTTP ${resp.code}: ${text.take(300)}")
                    return@withContext null
                }
                val parsed = try {
                    json.decodeFromString(AsrResponse.serializer(), text)
                } catch (e: Exception) {
                    Log.e(TAG, "响应解析失败: ${text.take(300)}", e)
                    return@withContext null
                }
                parsed.text?.trim()?.ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别异常", e)
            null
        } finally {
            // 识别完就删，不然缓存目录会越积越多
            runCatching { wav.delete() }
        }
    }
}
