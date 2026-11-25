package com.aichat.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aichat.app.ui.chat.ChatScreen
import com.aichat.app.ui.chat.ChatViewModel
import com.aichat.app.ui.chat.RecordingState
import com.aichat.app.ui.chat.InputBar
import com.aichat.app.ui.theme.AIChatTheme
import com.aichat.app.ui.theme.LocalChatColors

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    /**
     * 音视频 + 通讯录 + 电话权限统一申请。
     *
     * 一次要齐：录音（语音输入）、通讯录（按名字打电话）、电话（直接拨出）。
     * 分开申请会让用户在不同时机被弹三次框，体验更差。
     * 用户拒绝也不阻塞使用 —— 对应功能会返回明确的「缺少权限」提示。
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
            ),
        )

        setContent {
            val state by vm.state.collectAsState()

            AIChatTheme(themeMode = state.settings.themeMode) {
                val colors = LocalChatColors.current

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.bgMain),
                ) {
                    ChatScreen(
                        title = state.currentTitle,
                        messages = state.messages,
                        text = state.input,
                        onTextChange = vm::onInputChange,
                        onSend = vm::send,
                        onStop = vm::stopStreaming,
                        isStreaming = state.isStreaming,
                        deepThinking = state.settings.deepThinking,
                        onToggleDeepThinking = vm::toggleDeepThinking,
                        recordingState = state.recording,
                        recordingSeconds = state.recordingSeconds,
                        onRecordStart = vm::startRecording,
                        onRecordStop = vm::stopRecording,
                        onRecordCancel = vm::cancelRecording,
                        speechAvailable = true,
                        helperText = state.helperText,
                        ttsEnabled = state.settings.ttsEnabled,
                        onToggleTts = vm::toggleTts,
                        onNewChat = vm::createConversation,
                        onOpenDrawer = { vm.openDrawer(true) },
                        onRetry = vm::retry,
                    )
                }
            }
        }
    }
}
