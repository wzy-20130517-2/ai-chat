package com.aichat.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aichat.app.core.audio.longPressToTalk
import com.aichat.app.ui.theme.ChatText
import com.aichat.app.ui.theme.LocalChatColors

/** 录音状态，由上层持有（真正录音在后端）。 */
enum class RecordingState { IDLE, RECORDING, CANCELING, TRANSCRIBING }

/**
 * 底部输入区。
 *
 * 结构（自下而上）：
 *   输入框（圆角胶囊，内嵌按钮）
 *   ├─ 长按录音手势（整块输入框区域）
 *   ├─ 「深度思考」开关（左下）
 *   └─ 发送 / 停止按钮（右下）
 *
 * 【为什么用 BasicTextField 而不是 TextField】
 * Material3 的 TextField 自带 label、下划线、内置 padding，想做成 ChatGPT 那种
 * 干净的无边框块要覆盖一堆默认值，还不一定干净。BasicTextField 从零画，
 * 一个 Box 加圆角背景就是最终效果，没有要对抗的默认样式。
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    deepThinking: Boolean,
    onToggleDeepThinking: () -> Unit,
    recordingState: RecordingState,
    recordingSeconds: Int,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    speechAvailable: Boolean,
    helperText: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // 录音中把键盘收起来 —— 不然屏幕上同时是键盘 + 录音浮层，很挤
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            keyboard?.hide()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgMain)
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        helperText?.let {
            Text(
                text = it,
                style = ChatText.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }

        // ────────── 输入框 ──────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.bgInput)
                .border(1.dp, colors.border, RoundedCornerShape(24.dp)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {

                // 文本区 / 录音提示
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp, max = 140.dp)
                        // 长按录音：只在可输入且没在流式输出时启用
                        .longPressToTalk(
                            enabled = speechAvailable && !isStreaming,
                            onClick = {
                                // 兜底：极端时序下（比如界面尚未完成首次组合）
                                // focusRequester 仍可能没有附着点，这里不该让 App 崩掉。
                                // 正常情况走不到 catch —— 输入框已改成常驻。
                                runCatching { focusRequester.requestFocus() }
                            },
                            onStart = onRecordStart,
                            onStop = onRecordStop,
                            onCancel = onRecordCancel,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    when (recordingState) {
                        RecordingState.RECORDING, RecordingState.CANCELING ->
                            RecordingIndicator(recordingSeconds, recordingState == RecordingState.CANCELING)
                        RecordingState.TRANSCRIBING ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.textTertiary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在识别…", style = ChatText.body, color = colors.textTertiary)
                            }
                        RecordingState.IDLE ->
                            // 【2026-11-25 修崩溃】BasicTextField 必须始终存在。
                            //
                            // 原来是 `if (text.isEmpty()) Text(占位) else BasicTextField(...)`，
                            // 而 focusRequester 只挂在 BasicTextField 上 —— 输入框为空时
                            // 那个节点根本没被组合出来，长按手势里的
                            // `focusRequester.requestFocus()` 就会抛
                            // "FocusRequester is not initialized"。
                            //
                            // 改成：输入框常驻，占位文字用 Box 叠在它上面（空文本时才显示）。
                            // 这样 focusRequester 永远有附着点。
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = if (speechAvailable) "发消息，或长按说话" else "发消息",
                                        style = ChatText.body,
                                        color = colors.textTertiary,
                                    )
                                }
                                BasicTextField(
                                    value = text,
                                    onValueChange = onTextChange,
                                    textStyle = ChatText.body.copy(color = colors.textPrimary),
                                    cursorBrush = SolidColor(colors.textPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .onPreviewKeyEvent { ev ->
                                            // Enter 发送（Shift+Enter 换行由 ImeAction 处理不了，
                                            // 手机上主要是回车即发送）
                                            if (ev.key == Key.Enter && ev.type == KeyEventType.KeyDown) {
                                                if (text.isNotBlank() && !isStreaming) {
                                                    onSend()
                                                }
                                                true
                                            } else false
                                        },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (text.isNotBlank() && !isStreaming) onSend()
                                        },
                                    ),
                                )
                            }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 工具行：深度思考 + 发送
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeepThinkingChip(deepThinking, onToggleDeepThinking, colors)

                    Spacer(Modifier.weight(1f))

                    if (isStreaming) {
                        // 流式中显示停止按钮
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(colors.textPrimary)
                                .clickable { onStop() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "停止",
                                tint = colors.bgMain,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        val canSend = text.isNotBlank() && text.length <= 8000
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (canSend) colors.textPrimary else colors.border)
                                .clickable(enabled = canSend) { onSend() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "发送",
                                tint = if (canSend) colors.bgMain else colors.textTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「深度思考」开关：一个带图标的胶囊按钮。 */
@Composable
private fun DeepThinkingChip(
    active: Boolean,
    onToggle: () -> Unit,
    colors: com.aichat.app.ui.theme.ChatColors,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) colors.bgHover else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) colors.border else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = null,
            tint = if (active) colors.textPrimary else colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "深度思考",
            style = ChatText.caption.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
            color = if (active) colors.textPrimary else colors.textTertiary,
        )
    }
}

/**
 * 录音中的浮层：一个脉动的麦克风 + 读秒 + 提示。
 *
 * 脉动动画跟音量无关（接入真实音量会让效果跳得很难看，chat-demo 也是固定节奏）。
 */
@Composable
private fun RecordingIndicator(seconds: Int, canceling: Boolean) {
    val colors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "rec")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(if (canceling) colors.danger else colors.textPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = colors.bgMain,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (canceling) "松开取消" else formatSeconds(seconds),
            style = ChatText.body.copy(fontWeight = FontWeight.Medium),
            color = if (canceling) colors.danger else colors.textPrimary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (canceling) "" else "松开发送，上滑取消",
            style = ChatText.caption,
            color = colors.textTertiary,
        )
    }
}

private fun formatSeconds(s: Int): String {
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "%d:%02d".format(m, sec) else "${sec}s"
}
