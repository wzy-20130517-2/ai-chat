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
            // 只读摘要，不解析每个会话的消息 —— 会话多了以后全量解析会拖慢启动
            val metas = store.listSessions()
            val convs = metas.map { m ->
                Conversation(
                    id = m.id,
                    title = m.title,
                    messages = emptyList(),   // 消息按需加载
                    createdAt = m.createdAt,
                    updatedAt = m.updatedAt,
                )
            }

            // 恢复上次正在聊的会话。
            // 优先用显式记录的 lastConversationId；它指向的会话可能已被删除，
            // 这时退回到最近更新的那个；都没有才新建。
            val current = convs.firstOrNull { it.id == settings.lastConversationId }
                ?: convs.firstOrNull()

            _state.value = _state.value.copy(
                settings = settings,
                conversations = convs,
                currentId = current?.id,
                // 只有当前会话需要读消息
                messages = current?.let { store.loadMessages(it.id) } ?: emptyList(),
            )

            if (current == null) {
                createConversation()
            } else if (current.id != settings.lastConversationId) {
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
        // 空会话不落盘 —— 文件在第一条消息追加时才创建，
        // 避免点几次「新对话」就在磁盘上堆一串空文件。
    }

    fun switchConversation(id: String) {
        if (_state.value.currentId == id) {
            _state.value = _state.value.copy(drawerOpen = false)
            return
        }
        tts.stop()
        streamJob?.cancel()
        _state.value = _state.value.copy(
            currentId = id,
            messages = emptyList(),
            input = "",
            isStreaming = false,
            drawerOpen = false,
        )
        persistLastConversation(id)
        // 消息按需从磁盘读 —— 会话多时不必启动就全量载入
        viewModelScope.launch {
            val msgs = store.loadMessages(id)
            if (_state.value.currentId == id) {
                _state.value = _state.value.copy(messages = msgs)
            }
        }
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
            store.deleteSession(id)
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

        // 【2026-11-25 修】只置 isStreaming 不够 —— 助手气泡的「三个点」动画
        // 看的是 message.streaming，取消后那条消息仍带着 streaming=true，
        // 于是全局已经停了、气泡还在转。
        // 这里把最后一条助手消息的 streaming 一并清掉。
        val list = _state.value.messages.toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && last.streaming) {
            list[list.lastIndex] = last.copy(streaming = false)
            _state.value = _state.value.copy(messages = list)
        }

        _state.value = _state.value.copy(isStreaming = false)
        // 用户主动停止：把最后状态整体落盘（此时消息可能已被上面改成非 streaming）
        _state.value.currentId?.let { id ->
            _state.value.conversations.firstOrNull { it.id == id }?.let { c ->
                val snap = _state.value.messages
                viewModelScope.launch { store.rewriteSession(c, snap) }
            }
        }
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

        _state.value = _state.value.copy(input = "", isStreaming = true, helperText = null)
        // 立刻落盘：用户消息一敲定就写，不等整轮结束
        pushMessage(userMsg)

        runTurn(_state.value.messages)
    }

    fun retry() {
        // 去掉最后一轮助手输出，用现有历史重跑。
        //
        // 末尾可能是「assistant + 若干 tool」，也可能只有 assistant ——
        // 要连同它产生的工具消息一起丢掉，否则重跑时会留下孤立的 tool 消息
        //（协议要求 tool 必须紧跟在带 tool_calls 的 assistant 之后）。
        val all = _state.value.messages
        var cut = all.size
        while (cut > 0 && (all[cut - 1].role == Role.ASSISTANT || all[cut - 1].role == Role.TOOL)) {
            cut--
        }
        val msgs = all.take(cut)
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
            try {
                val assistantId = UUID.randomUUID().toString()
                var assistantMsg = Message(
                    id = assistantId,
                    role = Role.ASSISTANT,
                    streaming = true,
                )
                // 占位消息只进内存，不进磁盘 —— 它还没有内容。
                // 等第一条正文/思维链/工具调用到达时再落盘（见下面的 persistAssistantOnce）。
                _state.value = _state.value.copy(
                    messages = _state.value.messages + assistantMsg,
                )
                var assistantPersisted = false

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
                    // 延迟到第一个正文字符才启动朗读。
                    // 工具轮可能全程没有正文（只返回 tool_calls），提前启动会白跑一次
                    // startStream + finish，既浪费一次合成又会被下一轮打断。
                    var ttsStarted = false

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
                                if (ttsOn) {
                                    if (!ttsStarted) {
                                        ttsStarted = true
                                        tts.startStream(_state.value.settings.ttsVoice)
                                    }
                                    tts.feed(ev.text)
                                }
                                assistantMsg = assistantMsg.copy(
                                    content = baseContent + turnContent.toString(),
                                    streaming = true,
                                )
                                replaceLast(assistantMsg)
                                if (!assistantPersisted) {
                                    assistantPersisted = true
                                    persistAssistant()
                                }
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

                    if (ttsStarted) tts.finish()

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

                    // 把这一轮的正文和工具结果都并入历史，进入下一轮。
                    //
                    // 【必须带上 toolCalls】OpenAI 协议要求 role=tool 的消息
                    // 紧跟在一条带 tool_calls 的 assistant 消息之后。
                    // 之前这里只写了 content、没写 toolCalls，于是：
                    //   assistant(content="") → tool(结果) → assistant(...)
                    // 这个形状是非法的，服务端会把 tool 消息忽略掉 ——
                    // 模型因此完全看不到「自己调用过 open_app、且已成功」，
                    // 于是反复调用同一个工具。
                    working += Message(
                        id = assistantId + "_r$rounds",
                        role = Role.ASSISTANT,
                        content = turnContent.toString(),
                        reasoning = turnReasoning.toString(),
                        toolCalls = pendingCalls.map { call ->
                            ToolCallRecord(
                                id = call.id,
                                name = call.function.name,
                                title = tools.describe(
                                    call.function.name,
                                    safeParse(call.function.arguments),
                                ),
                                status = ToolStatus.SUCCESS,
                                result = toolResults.firstOrNull { it.first.id == call.id }
                                    ?.second.orEmpty(),
                                arguments = call.function.arguments,
                            )
                        },
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

                    // 工具轮结束：补写一次助手消息（含正文 + 工具卡片结果 + 工具结果消息）
                    persistAssistant(toolResults.map { (call, content) ->
                        Message(
                            id = call.id,
                            role = Role.TOOL,
                            content = content,
                            toolName = call.function.name,
                            toolCallId = call.id,
                        )
                    })

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

            } finally {
                // 协程被取消（用户点停止）时会跳过上面所有收尾逻辑，
                // 所以 isStreaming / message.streaming 必须在这里兜底清掉，
                // 否则发送按钮会一直显示成「停止」、气泡三个点继续动画。
                val l = _state.value.messages.toMutableList()
                val last = l.lastOrNull()
                if (last != null && last.role == Role.ASSISTANT && last.streaming) {
                    l[l.lastIndex] = last.copy(streaming = false)
                    _state.value = _state.value.copy(messages = l)
                }
                _state.value = _state.value.copy(isStreaming = false, helperText = null)
            }
            // 整轮结束：整体落盘一次，保证最终状态完整
            _state.value.currentId?.let { id ->
                _state.value.conversations.firstOrNull { it.id == id }?.let { c ->
                    val snap = _state.value.messages
                    viewModelScope.launch { store.rewriteSession(c, snap) }
                }
            }
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
                                        // 用真实参数。写死 "{}" 会让模型回看历史时
                                        // 看到自己用了空参数，从而重新调用工具。
                                        arguments = tc.arguments.ifBlank { "{}" },
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

    /**
     * 落盘助手消息（可能被后续轮次继续追加内容）。
     *
     * 【为什么单独一条路】用户消息和工具消息是「一次定型」的，追加即可。
     * 助手消息会经历「正文 → 工具调用 → 再正文」多次变化，
     * 追加式写会留下多个半成品版本。所以对它采用「重写整个文件」——
     * 一次会话里这种重写最多每轮一次（工具轮数上限 8），成本可接受。
     */
    private fun persistAssistant(toolMessages: List<Message> = emptyList()) {
        val id = _state.value.currentId ?: return
        val conv = _state.value.conversations.firstOrNull { it.id == id } ?: return

        // 落盘时要把工具消息拼进去。
        //
        // 【为什么 UI 状态里没有它们】Role.TOOL 是「喂给模型」的内部消息，
        // 显示在气泡里没有意义（工具结果已经由助手消息的 toolCalls 卡片呈现）。
        // 但**必须存盘** —— 否则会话恢复后模型看不到自己调用过什么，
        // 会重新调用同一个工具（「让它打开微信，它反复调用」就是这个原因）。
        // 所以 UI 只存可见消息，磁盘存「可见消息 + 工具消息」。
        // 工具消息必须排在「本条助手消息」之前：
        // 正确的顺序是 assistant(带 tool_calls) → tool(结果) → assistant(最终正文)。
        // 而 _state.messages 的最后一条正是本条助手消息（会被持续更新），
        // 所以把它拆出来放到工具消息后面。
        val all = _state.value.messages
        val snapshot = if (toolMessages.isEmpty()) {
            all
        } else {
            val last = all.lastOrNull()
            if (last != null && last.role == Role.ASSISTANT) {
                all.dropLast(1) + toolMessages + last
            } else {
                all + toolMessages
            }
        }

        viewModelScope.launch {
            store.rewriteSession(conv, snapshot)
        }
    }

    /**
     * 往对话流里压一条消息，并**立刻**落盘。
     *
     * 对齐 CCM CLI 的 Agent._pushMessage：每条消息一产生就 appendMessage，
     * 所以「工具跑到一半进程挂了」最多丢当前那一条，不会像攒批那样整轮全丢。
     * 这是唯一的高频写盘点。
     */
    private fun pushMessage(msg: Message) {
        val id = _state.value.currentId ?: return
        _state.value = _state.value.copy(messages = _state.value.messages + msg)

        val conv = _state.value.conversations.firstOrNull { it.id == id } ?: return
        val title = computeTitle(conv.title, _state.value.messages)
        if (title != conv.title) {
            _state.value = _state.value.copy(
                conversations = _state.value.conversations.map {
                    if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }

        // 用户消息用 append（增量写，O(1)）。
        //
        // 【为什么这里不用 rewrite】用户消息是「一次定型」的，追加即可，
        // 没必要为它重写整份历史（几百条时是几百 KB）。
        // 助手消息会反复变化，那条路才用 rewrite（见 persistAssistant）。
        //
        // 【顺序问题怎么解决】写操作在 Store 内部有 Mutex 串行化；
        // 且 rewrite 写的是「当前完整内存状态」，本来就把用户消息包含在内，
        // 所以即使 rewrite 后执行，内容也不会丢。
        val updatedConv = _state.value.conversations.firstOrNull { it.id == id } ?: conv
        viewModelScope.launch {
            store.appendMessage(updatedConv, msg)
            store.updateMeta(id, title = updatedConv.title, updatedAt = updatedConv.updatedAt)
            store.prune(keepId = id)
        }
    }

    /** 标题取第一条用户消息前 20 字；已有标题不覆盖。 */
    private fun computeTitle(current: String, messages: List<Message>): String {
        if (current.isNotBlank() && current != "新对话") return current
        val first = messages.firstOrNull { it.role == Role.USER }?.content?.trim()
        return first?.take(20)?.ifBlank { "新对话" } ?: "新对话"
    }

    override fun onCleared() {
        super.onCleared()
        tts.stop()
        recorder.cancel()
    }
}
