package com.aichat.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aichat.app.ui.chat.ChatDrawer
import com.aichat.app.ui.chat.ChatScreen
import com.aichat.app.ui.chat.ChatViewModel
import com.aichat.app.ui.theme.AIChatTheme
import com.aichat.app.ui.theme.LocalChatColors

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    /**
     * 录音 / 通讯录 / 电话权限一次要齐。
     *
     * 分三次弹框对用户更烦；一起申请，拒绝某项只是那个功能不可用，
     * 对应工具会返回明确的「缺少权限」而不是崩。
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

                    // ── 会话抽屉 ──
                    AnimatedVisibility(
                        visible = state.drawerOpen,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { vm.openDrawer(false) },
                        )
                    }

                    AnimatedVisibility(
                        visible = state.drawerOpen,
                        enter = slideInHorizontally(initialOffsetX = { -it }),
                        exit = slideOutHorizontally(targetOffsetX = { -it }),
                    ) {
                        ChatDrawer(
                            conversations = state.conversations,
                            currentId = state.currentId,
                            themeMode = state.settings.themeMode,
                            onSelect = vm::switchConversation,
                            onDelete = vm::deleteConversation,
                            onNewChat = vm::createConversation,
                            onThemeChange = vm::setThemeMode,
                            onClose = { vm.openDrawer(false) },
                        )
                    }
                }
            }
        }
    }
}
