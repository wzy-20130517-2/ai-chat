package com.aichat.app.core.data

import kotlinx.serialization.Serializable

/** 主题模式：默认跟随系统。 */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

/**
 * 一条消息。
 *
 * 时间戳只在内部用于排序与展示「刚才/昨天」，不渲染在用户气泡内
 * （需求：用户气泡不显示时间）。
 */
@Serializable
data class Message(
    val id: String,
    val role: Role,
    val content: String = "",
    /** 思维链（深度思考开启时的 reasoning 内容）。 */
    val reasoning: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** 工具调用记录，用于在气泡里渲染工具卡片。 */
    val toolCalls: List<ToolCallRecord> = emptyList(),
    /** 流式生成中标记，用于 UI 显示光标/动画。 */
    val streaming: Boolean = false,
    /** 生成失败时的错误信息。 */
    val error: String? = null,
    /** role=TOOL 时，这是哪个工具产生的结果。 */
    val toolName: String = "",
    /** role=TOOL 时，对应的 tool_call id（回传给 API 必须带）。 */
    val toolCallId: String = "",
)

@Serializable
data class ToolCallRecord(
    val id: String,
    val name: String,
    /** 展示给用户的简短说明，如「搜索：明天的天气」。 */
    val title: String,
    val status: ToolStatus = ToolStatus.RUNNING,
    val result: String = "",
)

@Serializable
enum class ToolStatus { RUNNING, SUCCESS, FAILED }

@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 深度思考按钮状态，默认关闭。 */
    val deepThinking: Boolean = false,
    /** TTS 自动朗读开关。 */
    val ttsEnabled: Boolean = false,
    val ttsVoice: String = "zh-CN-XiaoxiaoNeural",
    /**
     * 上次正在聊的会话 id。启动时优先恢复它。
     *
     * 为什么不用「updatedAt 最新的那个」：新建一个空会话也会刷新 updatedAt，
     * 于是「上次在聊 A，中途点了一下新对话就退出」会导致 A 丢失焦点。
     * 显式记录实际进入过的会话更准确。
     */
    val lastConversationId: String? = null,
)
