package com.aichat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aichat.app.core.data.Message
import com.aichat.app.ui.theme.ChatText
import com.aichat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 对话主界面。
 *
 * 【粘性滚动】
 * 这是聊天界面最容易做错的地方，需求里也特别提了「粘性滑动 / 脱离跟随」：
 *   - 用户停在底部 → 新内容到达时自动滚到底，跟随
 *   - 用户往上翻 → 立刻脱离跟随，新内容不再拉回底部（否则读历史时一直被弹走）
 *   - 用户滚回底部附近 → 恢复跟随
 *   - 脱离跟随时显示「回到底部」悬浮按钮
 *
 * 判定用「最后一个可见项距离列表末尾」而不是「isScrolledToBottom」——
 * 后者在列表内容不足一屏时永远是 true，但用户可能正看着上面。
 */
@Composable
fun ChatScreen(
    title: String,
    messages: List<Message>,
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
    ttsEnabled: Boolean,
    onToggleTts: () -> Unit,
    onNewChat: () -> Unit,
    onOpenDrawer: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalChatColors.current
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // 「是否跟随底部」。初值 true，因为打开会话默认就该在底部。
    val shouldFollow by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // 最后一项的底部 - 视口底部 ≤ 阈值 → 认为在底部附近
            val gap = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
            gap < with(density) { 120.dp.toPx() }
        }
    }

    // 自动滚动：只在「跟随」状态下、且内容变化时滚到底
    LaunchedEffect(listState, shouldFollow) {
        snapshotFlow { messages.size to messages.lastOrNull()?.content?.length }
            .filter { shouldFollow }
            .collect {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.lastIndex)
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bgMain)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            TopBar(
                title = title,
                ttsEnabled = ttsEnabled,
                onToggleTts = onToggleTts,
                onNewChat = onNewChat,
                onOpenDrawer = onOpenDrawer,
            )

            // ── 消息区 ──
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(colors)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                deepThinking = deepThinking,
                                onRetry = onRetry,
                            )
                        }
                    }
                }

                // 脱离跟随时浮现的「回到底部」
                // 显式写全包名：import 的 AnimatedVisibility 是 ColumnScope 上的扩展版，
                // 而这里在 Box 作用域里（外层虽然有个 Column，但那是另一个层）——
                // 不限定会报「cannot be called with an implicit receiver」。
                androidx.compose.animation.AnimatedVisibility(
                    visible = !shouldFollow,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    val scope = rememberCoroutineScope()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.bgElevated)
                            .clickable {
                                scope.launch {
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.lastIndex)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "回到底部",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            InputBar(
                text = text,
                onTextChange = onTextChange,
                onSend = onSend,
                onStop = onStop,
                isStreaming = isStreaming,
                deepThinking = deepThinking,
                onToggleDeepThinking = onToggleDeepThinking,
                recordingState = recordingState,
                recordingSeconds = recordingSeconds,
                onRecordStart = onRecordStart,
                onRecordStop = onRecordStop,
                onRecordCancel = onRecordCancel,
                speechAvailable = speechAvailable,
                helperText = helperText,
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    ttsEnabled: Boolean,
    onToggleTts: () -> Unit,
    onNewChat: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val colors = LocalChatColors.current
    // 顶栏避让状态栏：edge-to-edge 下直接放在 Column 顶部会顶到屏幕最上沿，
    // 按钮被状态栏盖住点不到。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 【2026-11-25 修重叠】
        // 之前左侧菜单和右侧按钮分别放在两个 fillMaxWidth() 的 Row 里，
        // 两个 Row 都铺满整行、互相叠在对方上面 —— 左侧那个透明的 Row
        // 盖住了右侧按钮的点击区，表现就是「点 TTS 却打开了侧栏」。
        //
        // 现在改成单个 Row：左右两侧各占固定宽度（各 2 个 40dp 槽位），
        // 标题用 weight(1f) 吃掉中间剩余空间并居中。
        // 两边等宽，标题才是真居中，不是「剩余空间的中心」。

        // 左侧槽位：菜单按钮 + 一个空槽（与右侧两个按钮对齐）
        Row(
            modifier = Modifier.width(88.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenDrawer() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Menu, "会话列表",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 标题：占中间剩余空间并居中
        Text(
            text = title,
            style = ChatText.title,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )

        // 右侧槽位：朗读 + 新对话（两个 40dp，与左侧宽度对称）
        Row(
            modifier = Modifier.width(88.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleTts() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = if (ttsEnabled) "关闭朗读" else "开启朗读",
                    tint = if (ttsEnabled) colors.textPrimary else colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onNewChat() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add, "新对话",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(colors: com.aichat.app.ui.theme.ChatColors) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "有什么可以帮你？",
                style = ChatText.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "可以让我搜索、打开应用、打电话",
                style = ChatText.bodySmall,
                color = colors.textTertiary,
            )
        }
    }
}
