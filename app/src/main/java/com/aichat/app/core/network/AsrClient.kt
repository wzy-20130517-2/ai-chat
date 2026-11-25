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
 * 接口是标准 OpenAI 转写格式（实测确认）：
 *   POST {base}/audio/transcriptions
 *   multipart: file=WAV, model=<型号>
 *   返回 {"text": "...", "duration": 1.0}
 *
 * 【型号轮换】
 * 实测 xingchenasr 几个型号都在线，但单型号偶尔会返回空文本或 5xx。
 * 按 Config.ASR_MODELS 顺序尝试，任一返回非空文本就采用。
 * 全部失败才返回 null。
 *
 * WAV 必须是 16kHz 单声道 16-bit PCM（AudioRecorder 产出的就是这个）。
 */
class AsrClient {

    companion object {
        private const val TAG = "AsrClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 上传 + 转写：接近 120 秒上限的录音约 3.8MB，还要等转写
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class AsrResponse(
        val text: String? = null,
        val duration: Double? = null,
    )

    /**
     * 识别一个 WAV 文件，型号按顺序轮换。
     *
     * @return 识别出的文本；全部型号都失败返回 null
     */
    suspend fun transcribe(wav: File): String? = withContext(Dispatchers.IO) {
        if (!wav.exists() || wav.length() == 0L) {
            Log.w(TAG, "音频文件为空")
            return@withContext null
        }

        try {
            for (model in Config.ASR_MODELS) {
                val text = tryModel(wav, model)
                if (!text.isNullOrBlank()) {
                    Log.i(TAG, "识别成功（$model）：${text.take(40)}")
                    return@withContext text
                }
                Log.w(TAG, "型号 $model 未返回文本，尝试下一个")
            }
            Log.e(TAG, "所有 ASR 型号都失败")
            null
        } finally {
            // 识别完就删，不然缓存目录会越积越多
            runCatching { wav.delete() }
        }
    }

    /** 用指定型号试一次。失败或空文本返回 null。 */
    private fun tryModel(wav: File, model: String): String? {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "recording.wav",
                    wav.asRequestBody("audio/wav".toMediaType()),
                )
                .addFormDataPart("model", model)
                .build()

            val request = Request.Builder()
                .url("${Config.ASR_BASE_URL.trimEnd('/')}/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${Config.ASR_API_KEY}")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "$model HTTP ${resp.code}: ${text.take(200)}")
                    return null
                }
                val parsed = try {
                    json.decodeFromString(AsrResponse.serializer(), text)
                } catch (e: Exception) {
                    Log.e(TAG, "$model 响应解析失败: ${text.take(200)}", e)
                    return null
                }
                parsed.text?.trim()?.ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$model 异常", e)
            null
        }
    }
}
