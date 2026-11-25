package com.aichat.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.core.audio.AudioRecorder
import com.aichat.app.core.audio.TtsPlayer
import com.aichat.app.core.data.AppSettings
import com.aichat.app.core.data.Conversation
import com.aichat.app.core.data.Message
import com.aichat.app.core.data.Role
import com.aichat.app.core.data.Store
import com.aichat.app.core.data.ThemeMode
import com.aichat.app.core.data.ToolCallRecord
import com.aichat.app.core.data.ToolStatus
import com.aichat.app.core.network.AsrClient
import com.aichat.app.core.network.ChatMessage
import com.aichat.app.core.network.Config
import com.aichat.app.core.network.LlmClient
import com.aichat.app.core.network.StreamEvent
import com.aichat.app.core.network.ToolCall
import com.aichat.app.core.tools.ToolRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 对话状态与业务逻辑。
 *
 * 【为什么逻辑都放 ViewModel 而不是 Composable】
 * 旋转屏幕、切后台都会重建 Activity/Composable，录音、流式请求、TTS 播放
 * 这些长生命周期的东西必须在 ViewModel 里，否则一转屏就全断。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val store = Store(app)
    private val tools = ToolRegistry(app)
    private val llm = LlmClient()
    private val asr = AsrClient()
    private val recorder = AudioRecorder()
    private val tts = TtsPlayer(app.cacheDir)

    // ─────────── 对外状态 ───────────

    data class UiState(
        val conversations: List<Conversation> = emptyList(),
        val currentId: String? = null,
        val messages: List<Message> = emptyList(),
        val input: String = "",
        val isStreaming: Boolean = false,
        val settings: AppSettings = AppSettings(),
        val recording: RecordingState = RecordingState.IDLE,
        val recordingSeconds: Int = 0,
        val helperText: String? = null,
        /** 会话抽屉是否打开。 */
        val drawerOpen: Boolean = false,
    ) {
        val currentTitle: String
            get() = conversations.firstOrNull { it.id == currentId }?.title ?: "新对话"
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var recordTimerJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = store.loadSettings()
            val convs = store.loadConversations()

            // 恢复上次正在聊的会话。
            // 优先用显式记录的 lastConversationId；它指向的会话可能已被删除，
            // 这时退回到最近更新的那个；都没有才新建。
            val current = convs.firstOrNull { it.id == settings.lastConversationId }
                ?: convs.maxByOrNull { it.updatedAt }

            _state.value = _state.value.copy(
                settings = settings,
                conversations = convs,
                currentId = current?.id,
                messages = current?.messages ?: emptyList(),
            )

            if (current == null) {
                createConversation()
            } else if (current.id != settings.lastConversationId) {
                // 修正记录，避免下次又走 fallback
                persistLastConversation(current.id)
            }
        }
    }

    /** 记录「当前正在聊的会话」，用于下次启动恢复。 */
    private fun persistLastConversation(id: String) {
        val s = _state.value.settings.copy(lastConversationId = id)
        _state.value = _state.value.copy(settings = s)
        viewModelScope.launch { store.saveSettings(s) }
    }

    // ─────────── 输入 ───────────

    fun onInputChange(s: String) {
        _state.value = _state.value.copy(input = s)
    }

    fun toggleDeepThinking() {
        val newSettings = _state.value.settings.copy(
            deepThinking = !_state.value.settings.deepThinking,
        )
        _state.value = _state.value.copy(settings = newSettings)
        viewModelScope.launch { store.saveSettings(newSettings) }
    }

    fun toggleTts() {
        val newSettings = _state.value.settings.copy(
            ttsEnabled = !_state.value.settings.ttsEnabled,
        )
        _state.value = _state.value.copy(settings = newSettings)
        viewModelScope.launch { store.saveSettings(newSettings) }
        if (!newSettings.ttsEnabled) tts.stop()
    }

    fun setThemeMode(mode: ThemeMode) {
        val s = _state.value.settings.copy(themeMode = mode)
        _state.value = _state.value.copy(settings = s)
        viewModelScope.launch { store.saveSettings(s) }
    }

    // ─────────── 会话管理 ───────────

    fun createConversation() {
        val conv = Conversation(id = UUID.randomUUID().toString())
        val list = listOf(conv) + _state.value.conversations
        _state.value = _state.value.copy(
            conversations = list,
            currentId = conv.id,
            messages = emptyList(),
            input = "",
            drawerOpen = false,
        )
        persistLastConversation(conv.id)
        viewModelScope.launch { store.saveConversations(list) }
    }

    fun switchConversation(id: String) {
        val conv = _state.value.conversations.firstOrNull { it.id == id } ?: return
        tts.stop()
        streamJob?.cancel()
        _state.value = _state.value.copy(
            currentId = id,
            messages = conv.messages,
            input = "",
            isStreaming = false,
            drawerOpen = false,
        )
        persistLastConversation(id)
    }

    fun deleteConversation(id: String) {
        val list = _state.value.conversations.filterNot { it.id == id }
        val needNew = _state.value.currentId == id
        _state.value = _state.value.copy(
            conversations = list,
            currentId = if (needNew) null else _state.value.currentId,
            messages = if (needNew) emptyList() else _state.value.messages,
        )
        viewModelScope.launch {
            store.saveConversations(list)
            if (needNew) createConversation()
        }
    }

    fun openDrawer(open: Boolean) {
        _state.value = _state.value.copy(drawerOpen = open)
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        tts.stop()
        _state.value = _state.value.copy(isStreaming = false)
        persistCurrent()
    }

    // ─────────── 录音 ───────────

    fun startRecording() {
        if (_state.value.recording != RecordingState.IDLE) return
        if (!recorder.start()) {
            _state.value = _state.value.copy(
                helperText = "无法开始录音（请检查麦克风权限）",
            )
            return
        }
        tts.stop()
        _state.value = _state.value.copy(
            recording = RecordingState.RECORDING,
            recordingSeconds = 0,
        )

        recordTimerJob?.cancel()
        recordTimerJob = viewModelScope.launch {
            while (true) {
                delay(200)
                // 直接读录音器上报的真实时长，不用自己数秒 —— 迟到/丢帧时更准
                val sec = (recorder.recordedMs / 1000).toInt()
                _state.value = _state.value.copy(recordingSeconds = sec)
                if (sec >= 120) {
                    // 到 ASR 上限，自动停止并转录
                    stopRecording()
                    break
                }
            }
        }
    }

    fun cancelRecording() {
        recordTimerJob?.cancel()
        recorder.cancel()
        _state.value = _state.value.copy(
            recording = RecordingState.IDLE,
            recordingSeconds = 0,
        )
    }

    fun stopRecording() {
        recordTimerJob?.cancel()

        val file = recorder.stop(getApplication<Application>().cacheDir)
        _state.value = _state.value.copy(
            recording = RecordingState.TRANSCRIBING,
            recordingSeconds = 0,
        )

        if (file == null) {
            _state.value = _state.value.copy(
                recording = RecordingState.IDLE,
                helperText = "没听到声音",
            )
            return
        }

        viewModelScope.launch {
            val text = asr.transcribe(file)
            _state.value = _state.value.copy(
                recording = RecordingState.IDLE,
                helperText = if (text == null) "识别失败，请重试" else null,
                // 识别结果追加到输入框（而不是直接发送）——
                // 识别难免有错，让用户能改一下再发
                input = if (text != null) {
                    if (_state.value.input.isBlank()) text else "${_state.value.input} $text"
                } else _state.value.input,
            )
        }
    }

    // ─────────── 发送 & 流式 ───────────

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.isStreaming) return

        val userMsg = Message(
            id = UUID.randomUUID().toString(),
            role = Role.USER,
            content = text,
            // 时间戳进模型上下文时带上（需求：每条消息附带当前时间给模型，但不显示在气泡内）
            timestamp = System.currentTimeMillis(),
        )

        val updated = _state.value.messages + userMsg
        _state.value = _state.value.copy(
            messages = updated,
            input = "",
            isStreaming = true,
            helperText = null,
        )
        persistCurrent()

        runTurn(updated)
    }

    fun retry() {
        // 去掉最后一条助手消息，用现有历史重跑
        val msgs = _state.value.messages.dropLastWhile { it.role == Role.ASSISTANT }
        if (msgs.isEmpty() || _state.value.isStreaming) return
        _state.value = _state.value.copy(messages = msgs, isStreaming = true)
        runTurn(msgs)
    }

    /**
     * 跑一轮对话，含工具调用循环。
     *
     * 【为什么要循环】
     * 模型可能连续调多个工具（先 list 应用、再 open），每轮工具结果都要回喂给它。
     * 循环上限 Config.MAX_TOOL_ROUNDS 防止模型陷入「调工具 → 不满意 → 再调」的死循环。
     */
    private fun runTurn(history: List<Message>) {
        streamJob = viewModelScope.launch {
            val assistantId = UUID.randomUUID().toString()
            var assistantMsg = Message(
                id = assistantId,
                role = Role.ASSISTANT,
                streaming = true,
            )
            _state.value = _state.value.copy(
                messages = _state.value.messages + assistantMsg,
            )

            val working = history.toMutableList()
            var rounds = 0

            while (rounds < Config.MAX_TOOL_ROUNDS) {
                rounds++
                var turnContent = StringBuilder()
                var turnReasoning = StringBuilder()
                val pendingCalls = mutableListOf<ToolCall>()
                var failed = false

                // 每轮从「空内容」起算。重试时 LlmClient 内部可能已经跑过几次，
                // 但那些内容不会到我们这里 —— 我们只在真正收到时累积。
                // 这里显式清空可以避免上一轮正文串到这一轮。
                val baseContent = assistantMsg.content
                val baseReasoning = assistantMsg.reasoning

                val ttsOn = _state.value.settings.ttsEnabled
                if (ttsOn) {
                    tts.startStream(_state.value.settings.ttsVoice)
                }

                val thinkingOn = _state.value.settings.deepThinking
                llm.stream(
                    messages = buildApiMessages(working),
                    tools = tools.specs(),
                    deepThinking = thinkingOn,
                    // 关闭思考时即使模型返回思维链也不展示（部分站默认会返回）
                    hideReasoning = !thinkingOn,
                ).collect { ev ->
                    when (ev) {
                        is StreamEvent.Content -> {
                            turnContent.append(ev.text)
                            if (ttsOn) tts.feed(ev.text)
                            assistantMsg = assistantMsg.copy(
                                content = baseContent + turnContent.toString(),
                                streaming = true,
                            )
                            replaceLast(assistantMsg)
                        }

                        is StreamEvent.Reasoning -> {
                            turnReasoning.append(ev.text)
                            assistantMsg = assistantMsg.copy(
                                reasoning = baseReasoning + turnReasoning.toString(),
                                streaming = true,
                            )
                            replaceLast(assistantMsg)
                        }

                        is StreamEvent.ToolCalls -> {
                            pendingCalls += ev.calls
                        }

                        is StreamEvent.Retrying -> {
                            _state.value = _state.value.copy(helperText = ev.message)
                        }

                        is StreamEvent.Failed -> {
                            assistantMsg = assistantMsg.copy(
                                streaming = false,
                                error = ev.message,
                            )
                            replaceLast(assistantMsg)
                            failed = true
                        }

                        is StreamEvent.Done -> Unit
                    }
                }

                if (ttsOn) tts.finish()

                if (failed) break

                // 没有工具调用 → 这一轮就是最终回复
                if (pendingCalls.isEmpty()) {
                    assistantMsg = assistantMsg.copy(streaming = false)
                    replaceLast(assistantMsg)
                    break
                }

                // ── 执行工具 ──
                val records = mutableListOf<ToolCallRecord>()
                val toolResults = mutableListOf<Pair<ToolCall, String>>()

                for (call in pendingCalls) {
                    val record = ToolCallRecord(
                        id = call.id,
                        name = call.function.name,
                        title = tools.describe(
                            call.function.name,
                            safeParse(call.function.arguments),
                        ),
                        status = ToolStatus.RUNNING,
                    )
                    records += record
                }
                assistantMsg = assistantMsg.copy(
                    toolCalls = assistantMsg.toolCalls + records,
                )
                replaceLast(assistantMsg)

                for ((idx, call) in pendingCalls.withIndex()) {
                    val result = tools.execute(call.function.name, call.function.arguments)
                    toolResults += call to result.content

                    val updatedRecords = assistantMsg.toolCalls.map {
                        if (it.id == call.id) {
                            it.copy(
                                status = if (result.ok) ToolStatus.SUCCESS else ToolStatus.FAILED,
                                title = result.displayTitle,
                                result = result.content,
                            )
                        } else it
                    }
                    assistantMsg = assistantMsg.copy(toolCalls = updatedRecords)
                    replaceLast(assistantMsg)
                }

                // 把这一轮的正文和工具结果都并入历史，进入下一轮
                working += Message(
                    id = assistantId + "_r$rounds",
                    role = Role.ASSISTANT,
                    content = turnContent.toString(),
                    reasoning = turnReasoning.toString(),
                )
                for ((call, content) in toolResults) {
                    working += Message(
                        id = call.id,
                        role = Role.TOOL,
                        content = content,
                        toolName = call.function.name,
                        toolCallId = call.id,
                    )
                }

                // 下一轮的助手消息（可能还有正文+工具）
                assistantMsg = assistantMsg.copy(streaming = true)
            }

            if (rounds >= Config.MAX_TOOL_ROUNDS) {
                assistantMsg = assistantMsg.copy(
                    streaming = false,
                    error = "工具调用次数过多，已停止。",
                )
                replaceLast(assistantMsg)
            }

            _state.value = _state.value.copy(isStreaming = false, helperText = null)
            persistCurrent()
        }
    }

    /** 把内部消息模型转成 API 的 message 数组。 */
    private fun buildApiMessages(history: List<Message>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()

        // 系统提示词：带上当前时间和工具使用倾向
        out += ChatMessage(
            role = "system",
            content = buildSystemPrompt(),
        )

        for (m in history) {
            when (m.role) {
                Role.USER -> {
                    // 用户消息带上时间（需求：附带给模型，但不显示在气泡里）
                    val time = formatTime(m.timestamp)
                    out += ChatMessage(role = "user", content = "[$time] ${m.content}")
                }

                Role.ASSISTANT -> {
                    if (m.content.isNotBlank() || m.toolCalls.isNotEmpty()) {
                        out += ChatMessage(
                            role = "assistant",
                            content = m.content.ifBlank { null },
                            toolCalls = m.toolCalls.map { tc ->
                                com.aichat.app.core.network.ToolCall(
                                    id = tc.id,
                                    function = com.aichat.app.core.network.FunctionCall(
                                        name = tc.name,
                                        arguments = "{}",
                                    ),
                                )
                            }.takeIf { it.isNotEmpty() },
                        )
                    }
                }

                Role.TOOL -> {
                    out += ChatMessage(
                        role = "tool",
                        content = m.content,
                        toolCallId = m.toolCallId,
                    )
                }

                Role.SYSTEM -> out += ChatMessage(role = "system", content = m.content)
            }
        }
        return out
    }

    private fun buildSystemPrompt(): String {
        val now = java.text.SimpleDateFormat(
            "yyyy年M月d日 EEEE HH:mm",
            java.util.Locale.CHINA,
        ).format(java.util.Date())

        return """
            你是 AI Chat，一个手机上用的中文助手。今天是 $now。

            行为准则：
            - 用简体中文回答，简洁直接，不要客套。
            - 需要最新信息、实时数据、或你不确定的事实时，用 web_search 搜索，不要凭记忆编。
            - 用户让你打开软件时用 open_app。不确定装没装可以先用 action=list 查。
            - 用户让你打电话时用 call_phone，按姓名传（系统会自动纠正同音字）。
              如果返回多个候选，要问用户拨给谁，不要自己选。
            - 不要声称你做了没有实际调用工具的事。
        """.trimIndent()
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ts))

    private fun safeParse(s: String): kotlinx.serialization.json.JsonObject =
        try {
            com.aichat.app.core.network.AppJson.decodeFromString(
                kotlinx.serialization.json.JsonObject.serializer(),
                s.ifBlank { "{}" },
            )
        } catch (_: Exception) {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }

    /** 替换列表最后一条（流式更新用）。 */
    private fun replaceLast(msg: Message) {
        val list = _state.value.messages.toMutableList()
        if (list.isNotEmpty()) {
            list[list.lastIndex] = msg
            _state.value = _state.value.copy(messages = list)
        }
    }

    private fun persistCurrent() {
        val s = _state.value
        val id = s.currentId ?: return
        val list = s.conversations.map { c ->
            if (c.id == id) {
                c.copy(
                    messages = s.messages,
                    updatedAt = System.currentTimeMillis(),
                    // 标题取第一条用户消息的前 20 字
                    title = c.title.ifBlank { "新对话" }.let { old ->
                        if (old == "新对话") {
                            s.messages.firstOrNull { it.role == Role.USER }
                                ?.content?.take(20)?.ifBlank { "新对话" } ?: "新对话"
                        } else old
                    },
                )
            } else c
        }
        _state.value = _state.value.copy(conversations = list)
        viewModelScope.launch { store.saveConversations(list) }
    }

    override fun onCleared() {
        super.onCleared()
        tts.stop()
        recorder.cancel()
    }
}
