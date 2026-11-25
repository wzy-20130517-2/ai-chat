package com.aichat.app.core.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式事件。UI 层消费这个，不用关心 SSE 细节。
 */
sealed class StreamEvent {
    /** 正文增量。 */
    data class Content(val text: String) : StreamEvent()

    /** 思维链增量（深度思考开启时会有）。 */
    data class Reasoning(val text: String) : StreamEvent()

    /** 模型要求调用工具。参数是完整 JSON 串（已累积完毕）。 */
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()

    /** 正常结束。 */
    data class Done(val finishReason: String) : StreamEvent()

    /** 失败（已重试到极限）。 */
    data class Failed(val message: String, val retryable: Boolean) : StreamEvent()

    /** 正在重试中，UI 可以显示「连接不稳，重试 2/4」。 */
    data class Retrying(val attempt: Int, val max: Int, val reason: String) : StreamEvent()
}

/**
 * OpenAI 兼容协议的流式对话客户端。
 *
 * 【为什么用 OkHttp 而不是 HttpURLConnection】
 * SSE 要边读边解析，HttpURLConnection 的流式读取在某些 Android 版本会整段缓冲，
 * 表现为「流式变成一次性吐完」。OkHttp 的 response.body.source() 是真正的增量读。
 *
 * 【自动重试策略】
 * 只重试「可重试」的错误：
 *   - 网络异常（IOException / 超时）
 *   - 429（限流）
 *   - 5xx（服务端错误）
 * 不重试 4xx（除 429）—— key 错了、参数错了，重试一万次也一样。
 * 指数退避 1s → 2s → 4s → 8s，最多 Config.MAX_RETRIES 次。
 *
 * 【重要：重试时会丢弃已收到的部分】
 * 已经吐出给 UI 的正文不能撤回，所以重试只发生在「还没收到任何正文」时。
 * 如果已经开始输出再断，就只能报错 —— 否则用户会看到内容重复一遍。
 */
class LlmClient(
    private val baseUrl: String = Config.LLM_BASE_URL,
    private val apiKey: String = Config.LLM_API_KEY,
    private val model: String = Config.LLM_MODEL,
) {

    companion object {
        private const val TAG = "LlmClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // 重试我们自己控制，避免叠加
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 发起一次流式对话。
     *
     * @param messages 完整对话历史（含工具结果）
     * @param tools 可用工具定义，null 表示本轮不给工具
     * @param deepThinking 是否请求思维链
     */
    fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>? = null,
        deepThinking: Boolean = false,
    ): Flow<StreamEvent> = callbackFlow {
        var attempt = 0
        var lastError = ""

        while (attempt <= Config.MAX_RETRIES) {
            var emittedAny = false

            try {
                val reqBody = ChatRequest(
                    model = model,
                    messages = messages,
                    stream = true,
                    tools = tools,
                    toolChoice = if (tools != null) "auto" else null,
                    enableThinking = if (deepThinking) true else null,
                )
                val bodyStr = json.encodeToString(ChatRequest.serializer(), reqBody)

                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(bodyStr.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errText = response.body?.string().orEmpty()
                    val code = response.code
                    response.close()

                    val msg = parseError(errText, code)
                    val retryable = code == 429 || code >= 500

                    if (retryable && attempt < Config.MAX_RETRIES) {
                        lastError = msg
                        attempt++
                        trySend(StreamEvent.Retrying(attempt, Config.MAX_RETRIES, msg))
                        delay(backoffMs(attempt))
                        continue
                    }
                    trySend(StreamEvent.Failed(msg, retryable))
                    close()
                    return@callbackFlow
                }

                // —— 解析 SSE ——
                val source = response.body!!.source()
                val toolAcc = mutableMapOf<Int, ToolAccumulator>()
                var finishReason = ""
                var failedMidStream = false

                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break

                    val chunk = try {
                        json.decodeFromString(StreamChunk.serializer(), payload)
                    } catch (e: Exception) {
                        // 有些中转站会插入非标准心跳，跳过而不是中断整条流
                        Log.w(TAG, "跳过无法解析的 chunk: ${payload.take(120)}")
                        continue
                    }

                    val choice = chunk.choices.firstOrNull() ?: continue
                    val delta = choice.delta

                    if (delta != null) {
                        val reasoning = delta.reasoningContent ?: delta.reasoning
                        if (!reasoning.isNullOrEmpty()) {
                            emittedAny = true
                            trySend(StreamEvent.Reasoning(reasoning))
                        }
                        val content = delta.content
                        if (!content.isNullOrEmpty()) {
                            emittedAny = true
                            trySend(StreamEvent.Content(content))
                        }
                        delta.toolCalls?.forEach { tc ->
                            emittedAny = true
                            toolAcc.getOrPut(tc.index) { ToolAccumulator() }.merge(tc)
                        }
                    }

                    if (choice.finishReason != null) {
                        finishReason = choice.finishReason
                    }
                }
                response.close()

                if (toolAcc.isNotEmpty()) {
                    trySend(StreamEvent.ToolCalls(toolAcc.toSortedMap().values.map { it.build() }))
                }
                trySend(StreamEvent.Done(finishReason.ifEmpty { "stop" }))
                close()
                return@callbackFlow

            } catch (e: CancellationException) {
                // 用户主动停止，不重试也不报错
                close()
                throw e
            } catch (e: IOException) {
                // 网络层异常：连接断、超时
                if (!emittedAny && attempt < Config.MAX_RETRIES) {
                    lastError = e.message ?: "网络错误"
                    attempt++
                    trySend(StreamEvent.Retrying(attempt, Config.MAX_RETRIES, lastError))
                    delay(backoffMs(attempt))
                    continue
                }
                trySend(StreamEvent.Failed("网络错误：${e.message}", true))
                close()
                return@callbackFlow
            } catch (e: Exception) {
                Log.e(TAG, "流式请求异常", e)
                if (!emittedAny && attempt < Config.MAX_RETRIES) {
                    lastError = e.message ?: "未知错误"
                    attempt++
                    trySend(StreamEvent.Retrying(attempt, Config.MAX_RETRIES, lastError))
                    delay(backoffMs(attempt))
                    continue
                }
                trySend(StreamEvent.Failed(lastError.ifEmpty { e.message ?: "未知错误" }, false))
                close()
                return@callbackFlow
            }
        }

        trySend(StreamEvent.Failed(lastError.ifEmpty { "重试次数用尽" }, true))
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun backoffMs(attempt: Int): Long =
        Config.RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 4))

    /** 从各种形状的错误体里抠出人能看懂的消息。 */
    private fun parseError(body: String, code: Int): String {
        if (body.isBlank()) return "HTTP $code"
        val msg = try {
            val obj = json.decodeFromString(ApiErrorBody.serializer(), body)
            obj.error?.message ?: obj.message
        } catch (_: Exception) {
            null
        }
        return when {
            !msg.isNullOrBlank() -> "HTTP $code：$msg"
            body.length > 200 -> "HTTP $code：${body.take(200)}"
            else -> "HTTP $code：$body"
        }
    }
}

/** 累积流式返回的工具调用（name 和 arguments 都是分片到达的）。 */
private class ToolAccumulator {
    private var id: String = ""
    private var name: String = ""
    private val args = StringBuilder()

    fun merge(tc: DeltaToolCall) {
        tc.id?.let { if (it.isNotEmpty()) id = it }
        tc.function?.name?.let { if (it.isNotEmpty()) name += it }
        tc.function?.arguments?.let { args.append(it) }
    }

    fun build(): ToolCall = ToolCall(
        id = id.ifEmpty { "call_${System.nanoTime()}" },
        function = FunctionCall(
            name = name,
            arguments = args.toString().ifEmpty { "{}" },
        ),
    )
}
