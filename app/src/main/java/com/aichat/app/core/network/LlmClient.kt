package com.aichat.app.core.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 流式事件。 */
sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()
    data class Done(val finishReason: String) : StreamEvent()
    data class Failed(val message: String) : StreamEvent()

    /** 正在换端点/重试，UI 显示进度。 */
    data class Retrying(val message: String) : StreamEvent()
}

/**
 * OpenAI 兼容协议的流式对话客户端，带三级 fallback。
 *
 * 【为什么必须有「首字超时」】
 * 之前只设了 readTimeout。问题在于：连接成功后，如果服务端开始慢慢吐心跳
 * （或者吐出极小的空 delta），readTimeout 永远不会触发 —— 用户看到的是
 * 转圈转到天荒地老。真正该管的是「多久没拿到有意义的内容」，
 * 所以这里单独盯第一个字，20 秒不来就判超时。
 *
 * 【重试分层】
 *   同一端点：首字超时 ×2、空回答 ×2
 *   跨端点：换下一个 LlmEndpoint
 * 两者叠加后最多 3 × 3 = 9 次尝试，全失败才报错。
 *
 * 【空回答为什么要重试】
 * 实测 glm-5.3-flash 在 max_tokens 偏小时会把 token 全用在 reasoning_content 上，
 * content 返回空串但 finish_reason=stop。这不是错误响应，但用户看到的是「AI 没说话」。
 * 这类情况静默重试一次往往就能拿到正文。
 */
class LlmClient {

    companion object {
        private const val TAG = "LlmClient"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 共享的 OkHttpClient。
     * readTimeout 设成整条流的上限，首字超时由 withTimeoutOrNull 单独控制。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.STREAM_TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // 重试由我们自己控制
        .build()

    /**
     * 发起一次流式对话，带完整的重试与降级。
     *
     * @param deepThinking 是否请求并展示思维链
     * @param hideReasoning 即使模型返回了思维链也不向上层发射（关闭思考时用）
     */
    fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>? = null,
        deepThinking: Boolean = false,
        hideReasoning: Boolean = false,
    ): Flow<StreamEvent> = callbackFlow {
        var lastError = ""

        for ((endpointIdx, endpoint) in Config.LLM_ENDPOINTS.withIndex()) {
            var timeoutTries = 0
            var emptyTries = 0

            while (true) {
                val attemptLabel = buildString {
                    append(endpoint.name)
                    if (timeoutTries > 0) append("（超时重试 $timeoutTries）")
                    if (emptyTries > 0) append("（空回答重试 $emptyTries）")
                }

                val outcome = runOnce(
                    endpoint = endpoint,
                    messages = messages,
                    tools = tools,
                    deepThinking = deepThinking,
                    hideReasoning = hideReasoning,
                    attemptLabel = attemptLabel,
                )

                when (outcome) {
                    is Outcome.Success -> {
                        close()
                        return@callbackFlow
                    }

                    is Outcome.FirstTokenTimeout -> {
                        lastError = outcome.message
                        if (timeoutTries < Config.FIRST_TOKEN_RETRIES) {
                            timeoutTries++
                            trySend(
                                StreamEvent.Retrying(
                                    "${endpoint.name} 响应超时，重试 $timeoutTries/${Config.FIRST_TOKEN_RETRIES}",
                                ),
                            )
                            delay(600)
                            continue
                        }
                        // 这个端点超时用尽 → 换下一个端点
                        break
                    }

                    is Outcome.EmptyReply -> {
                        lastError = outcome.message
                        if (emptyTries < Config.EMPTY_REPLY_RETRIES) {
                            emptyTries++
                            trySend(
                                StreamEvent.Retrying(
                                    "${endpoint.name} 返回空内容，重试 $emptyTries/${Config.EMPTY_REPLY_RETRIES}",
                                ),
                            )
                            delay(400)
                            continue
                        }
                        break
                    }

                    is Outcome.HardFail -> {
                        lastError = outcome.message
                        break
                    }
                }
            }

            // 还有下一个端点就提示用户正在切换
            if (endpointIdx < Config.LLM_ENDPOINTS.lastIndex) {
                trySend(StreamEvent.Retrying("${endpoint.name} 不可用，切换到备用线路…"))
                delay(400)
            }
        }

        trySend(StreamEvent.Failed(lastError.ifEmpty { "所有线路都不可用，请稍后重试" }))
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private sealed class Outcome {
        object Success : Outcome()
        data class FirstTokenTimeout(val message: String) : Outcome()
        data class EmptyReply(val message: String) : Outcome()
        data class HardFail(val message: String) : Outcome()
    }

    /**
     * 在一个端点上跑一次完整请求。
     *
     * 首字超时用 withTimeoutOrNull 包住「第一次有意义的输出」，
     * 拿到第一个字之后就没有首字超时了（后续靠 readTimeout 兜）。
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.runOnce(
        endpoint: Config.LlmEndpoint,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        deepThinking: Boolean,
        hideReasoning: Boolean,
        attemptLabel: String,
    ): Outcome {
        var gotFirstToken = false
        var contentLen = 0
        var toolCallCount = 0
        var finishReason = ""

        try {
            val reqBody = ChatRequest(
                model = endpoint.model,
                messages = messages,
                stream = true,
                tools = tools,
                toolChoice = if (tools != null) "auto" else null,
                maxTokens = Config.MAX_OUTPUT_TOKENS,
                // 关思考传 "none"（实测本站返回 reasoning_len=0，真的不再思考）。
                // 开关思考时给 "medium"。仍然保留 hideReasoning 兜底：
                // 换到别的站点时 "none" 未必认，靠展示层过滤保证用户看不到。
                reasoningEffort = if (deepThinking) "medium" else "none",
            )
            val bodyStr = json.encodeToString(ChatRequest.serializer(), reqBody)

            val request = Request.Builder()
                .url("${endpoint.baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer ${endpoint.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errText = response.body?.string().orEmpty()
                val code = response.code
                response.close()
                return Outcome.HardFail(parseError(errText, code))
            }

            val source = response.body!!.source()
            val toolAcc = mutableMapOf<Int, ToolAccumulator>()

            try {
                // 【2026-11-25 改】不用 `while (!source.exhausted())`。
                // exhausted() 在流式连接上语义不可靠：数据尚未到达时它可能
                // 立刻返回 true（表现为整个循环一次都不执行 → 判定空回答）。
                // 改成无限循环，靠 readNextDataLine 返回 null 来识别真正的 EOF。
                while (true) {
                    // 首字超时只作用于「还没拿到任何有意义输出」的阶段。
                    //
                    // 【2026-11-25 修】原来写的是 `if (r === TIMEOUT)` ——
                    // withTimeoutOrNull 超时返回的是 null，永远不会等于那个哨兵对象，
                    // 于是超时被当成「流结束」直接 break，上层判定成空回答。
                    // 现在先用一个可区分的包装，把「超时」和「流正常结束」分开。
                    val line = if (!gotFirstToken) {
                        val r = withTimeoutOrNull(Config.FIRST_TOKEN_TIMEOUT_SEC * 1000) {
                            readNextDataLine(source, json)
                        }
                        if (r === null) {
                            // 20 秒没等到第一个 data 行 —— 这才是超时
                            runCatching { response.close() }
                            return Outcome.FirstTokenTimeout(
                                "${endpoint.name}（${endpoint.model}）${Config.FIRST_TOKEN_TIMEOUT_SEC}秒无响应",
                            )
                        }
                        r
                    } else {
                        readNextDataLine(source, json) ?: break
                    }

                    if (line == DONE) break

                    val chunk = try {
                        json.decodeFromString(StreamChunk.serializer(), line)
                    } catch (e: Exception) {
                        continue
                    }

                    val choice = chunk.choices.firstOrNull() ?: continue
                    val delta = choice.delta

                    if (delta != null) {
                        // 思维链：只在开启深度思考时展示
                        val reasoning = delta.reasoningContent ?: delta.reasoning
                        if (!reasoning.isNullOrEmpty() && !hideReasoning && deepThinking) {
                            gotFirstToken = true
                            trySend(StreamEvent.Reasoning(reasoning))
                        }

                        val content = delta.content
                        if (!content.isNullOrEmpty()) {
                            gotFirstToken = true
                            contentLen += content.length
                            trySend(StreamEvent.Content(content))
                        }

                        delta.toolCalls?.forEach { tc ->
                            gotFirstToken = true
                            toolAcc.getOrPut(tc.index) { ToolAccumulator() }.merge(tc)
                        }
                    }

                    if (choice.finishReason != null) finishReason = choice.finishReason
                }
            } finally {
                runCatching { response.close() }
            }

            toolCallCount = toolAcc.size
            if (toolAcc.isNotEmpty()) {
                trySend(StreamEvent.ToolCalls(toolAcc.toSortedMap().values.map { it.build() }))
            }

            // ── 空回答判定 ──
            // 正文为空、也没有工具调用 → 视为空回答，值得重试
            if (contentLen == 0 && toolCallCount == 0) {
                // 但如果模型明确说要调用工具（finish_reason=tool_calls）就不算空
                if (finishReason == "tool_calls") {
                    return Outcome.EmptyReply("${endpoint.name} 声称要调用工具但没给出调用内容")
                }
                return Outcome.EmptyReply("${endpoint.name}（${endpoint.model}）返回了空内容")
            }

            trySend(StreamEvent.Done(finishReason.ifEmpty { "stop" }))
            return Outcome.Success

        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Outcome.HardFail("${endpoint.name} 网络错误：${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "请求异常", e)
            return Outcome.HardFail("${endpoint.name} 请求异常：${e.message}")
        }
    }

    /**
     * 读下一行 `data:` 内容，跳过空行/心跳/注释行。
     *
     * 返回：
     *   - 非空 payload：正常的 chunk
     *   - [DONE]：服务端发了 data:[DONE]
     *   - null：流真的结束了（EOF）
     *
     * 注意：跳过心跳行是**内部循环**，不会因为跳过就返回 null ——
     * 那样会被上层当成「流结束」，长耗时模型（先思考再回答）会在
     * 首字回来之前就被误判成空回答。
     */
    private fun readNextDataLine(
        source: okio.BufferedSource,
        json: Json,
    ): String? {
        while (true) {
            val raw = source.readUtf8Line() ?: return null
            if (raw.isBlank()) continue
            if (!raw.startsWith("data:")) continue
            val payload = raw.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") return DONE
            return payload
        }
    }

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
            body.length > 160 -> "HTTP $code：${body.take(160)}"
            else -> "HTTP $code：$body"
        }
    }
}

private const val DONE = "__DONE__"

/** 累积分片到达的工具调用。 */
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
