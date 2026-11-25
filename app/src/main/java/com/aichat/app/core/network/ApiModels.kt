package com.aichat.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 全局 JSON 配置：忽略未知字段，避免服务端加字段就崩。 */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

// ─────────────────── 请求侧 ───────────────────

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val tools: List<ToolSpec>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /**
     * 推理强度（OpenAI 协议字段）。
     *
     * 关思考传 "none" —— 实测本站会返回 reasoning_len=0，真正不再产生思维链。
     * 其他取值（minimal/low/medium/high）都会让模型思考并把 reasoning_content 回传。
     */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /** 部分模型把思维链回传给服务端要求带这个字段。 */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String = "{}",
)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject,
)

// ─────────────────── 响应侧（流式） ───────────────────

@Serializable
data class StreamChunk(
    val id: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    /** 流式响应用这个。 */
    val delta: Delta? = null,
    /** 非流式响应（服务端无视 stream=true 时）用这个。 */
    val message: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    /** 思维链增量。字段名各家中转站不统一，解析时两个都查。 */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null,
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
data class DeltaFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

// ─────────────────── 错误 ───────────────────

@Serializable
data class ApiErrorBody(
    val error: ApiErrorDetail? = null,
    val message: String? = null,
)

@Serializable
data class ApiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
