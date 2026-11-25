package com.aichat.app.core.tools

import kotlinx.serialization.json.JsonObject

/**
 * 工具定义 + 执行结果的统一抽象。
 */
interface Tool {
    /** 工具名（给模型看的，英文小写下划线）。 */
    val name: String

    /** 描述（给模型看的，决定它什么时候用这个工具）。 */
    val description: String

    /** JSON Schema 参数定义。 */
    val parameters: JsonObject

    /** 执行。返回值会作为 tool 消息回给模型。 */
    suspend fun execute(args: JsonObject): ToolResult
}

/**
 * 执行结果。
 *
 * displayTitle 是给用户看的简短说明（渲染在工具卡片上），
 * ok=false 时模型会看到失败原因并可能重试别的路子。
 */
data class ToolResult(
    val ok: Boolean,
    val content: String,
    val displayTitle: String,
    /** 是否需要用户在系统弹窗里确认（目前只有打电话用）。 */
    val needsConfirm: Boolean = false,
)

/** 工具执行期间抛出的可读错误。 */
class ToolException(message: String) : Exception(message)
