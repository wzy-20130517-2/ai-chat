package com.aichat.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.app.core.data.Message
import com.aichat.app.core.data.Role
import com.aichat.app.core.data.ToolCallRecord
import com.aichat.app.core.data.ToolStatus
import com.aichat.app.ui.theme.ChatText
import com.aichat.app.ui.theme.LocalChatColors

/**
 * 一条消息气泡。
 *
 * 布局对齐 ChatGPT：
 *   - 用户消息：右侧、浅灰圆角块、最大宽度约 78%
 *   - 助手消息：左侧、无背景（直接铺在页面上）、占满可用宽度
 * 助手的思考链和工具卡片都挂在助手消息内部，用分隔线区隔。
 */
@Composable
fun MessageBubble(
    message: Message,
    deepThinking: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current

    when (message.role) {
        Role.USER -> UserBubble(message, colors)
        Role.ASSISTANT -> AssistantBubble(message, colors, deepThinking, onRetry)
        else -> Unit
    }
}

@Composable
private fun UserBubble(message: Message, colors: com.aichat.app.ui.theme.ChatColors) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.bgBubbleUser)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // 用户气泡内不显示时间（需求）
            Text(
                text = message.content,
                style = ChatText.body,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    message: Message,
    colors: com.aichat.app.ui.theme.ChatColors,
    deepThinking: Boolean,
    onRetry: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // —— 思维链 ——
        // 只在「深度思考开着」或「这条消息确实有思考内容」时显示。
        // 关掉深度思考后历史消息的思考链也保留 —— 那是已经发生的事，不该凭空消失。
        if (message.reasoning.isNotBlank()) {
            ThinkingBlock(message.reasoning, colors)
            Spacer(Modifier.height(8.dp))
        }

        // —— 工具调用卡片 ——
        if (message.toolCalls.isNotEmpty()) {
            message.toolCalls.forEach { tc ->
                ToolCallCard(tc, colors)
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(2.dp))
        }

        // —— 正文 ——
        if (message.content.isNotBlank()) {
            MarkdownText(
                text = message.content,
                color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // —— 流式光标 ——
        if (message.streaming && message.content.isBlank() && message.reasoning.isBlank()
            && message.toolCalls.isEmpty()
        ) {
            TypingIndicator(colors)
        }

        // —— 错误 ——
        message.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.danger.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = err,
                    style = ChatText.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.weight(1f),
                )
                if (onRetry != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "重试",
                        style = ChatText.bodySmall,
                        color = colors.danger,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onRetry() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 思维链展示块。默认折叠成一行「已深度思考」，点开看全文。
 *
 * 折叠而不是直接展开：思考过程通常很长，全展开会把正文挤到屏幕外。
 * 用户想看细节时点一下即可。
 */
@Composable
private fun ThinkingBlock(reasoning: String, colors: com.aichat.app.ui.theme.ChatColors) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) "深度思考" else "已深度思考",
                style = ChatText.caption,
                color = colors.textSecondary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = reasoning,
                style = ChatText.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** 工具调用卡片：一个图标 + 一行说明 + 状态。 */
@Composable
private fun ToolCallCard(tc: ToolCallRecord, colors: com.aichat.app.ui.theme.ChatColors) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .clickable { if (tc.result.isNotBlank()) expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (tc.status) {
                ToolStatus.RUNNING -> Spinner()
                ToolStatus.SUCCESS -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                ToolStatus.FAILED -> Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = when (tc.name) {
                    "call_phone" -> Icons.Default.Phone
                    "web_search" -> Icons.Default.Search
                    "open_app" -> Icons.Default.Apps
                    else -> Icons.Default.Apps
                },
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tc.title,
                style = ChatText.bodySmall,
                color = if (tc.status == ToolStatus.FAILED) colors.danger else colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (tc.result.isNotBlank()) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded && tc.result.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = tc.result,
                style = ChatText.caption,
                color = colors.textTertiary,
            )
        }
    }
}

/** 小小的旋转加载图标。 */
@Composable
private fun Spinner() {
    val colors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .rotate(angle)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.textTertiary),
    )
}

/** 助手还没吐出第一个字时的三连点动画。 */
@Composable
private fun TypingIndicator(colors: com.aichat.app.ui.theme.ChatColors) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.textTertiary.copy(alpha = alpha)),
            )
        }
    }
}
