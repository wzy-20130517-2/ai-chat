package com.aichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.app.core.data.Conversation
import com.aichat.app.core.data.ThemeMode
import com.aichat.app.ui.theme.ChatText
import com.aichat.app.ui.theme.LocalChatColors

/**
 * 会话列表抽屉。
 *
 * 结构：
 *   顶部「新对话」按钮
 *   中部会话列表（长按删除）
 *   底部主题切换（跟随系统 / 浅色 / 深色）
 */
@Composable
fun ChatDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    themeMode: ThemeMode,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewChat: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Column(
        modifier = modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(colors.bgSidebar),
    ) {
        // 顶部：标题 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "对话",
                style = ChatText.title,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close, "关闭",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 新对话
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onNewChat() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add, null,
                tint = colors.textPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("新对话", style = ChatText.bodySmall, color = colors.textPrimary)
        }

        Spacer(Modifier.height(4.dp))

        // 会话列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(conversations, key = { it.id }) { conv ->
                val selected = conv.id == currentId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) colors.bgHover else colors.bgSidebar)
                        .clickable { onSelect(conv.id) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = conv.title.ifBlank { "新对话" },
                        style = ChatText.bodySmall,
                        color = if (selected) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onDelete(conv.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close, "删除",
                                tint = colors.textTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // 底部：主题切换
        ThemeSelector(themeMode, onThemeChange, colors)
    }
}

@Composable
private fun ThemeSelector(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
    colors: com.aichat.app.ui.theme.ChatColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bgMain)
            .padding(8.dp),
    ) {
        Text(
            text = "外观",
            style = ChatText.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp),
        )
        ThemeOption(ThemeMode.SYSTEM, "跟随系统", Icons.Default.SettingsBrightness, current, onChange, colors)
        ThemeOption(ThemeMode.LIGHT, "浅色", Icons.Default.LightMode, current, onChange, colors)
        ThemeOption(ThemeMode.DARK, "深色", Icons.Default.DarkMode, current, onChange, colors)
    }
}

@Composable
private fun ThemeOption(
    mode: ThemeMode,
    label: String,
    icon: ImageVector,
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
    colors: com.aichat.app.ui.theme.ChatColors,
) {
    val selected = mode == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.bgHover else colors.bgMain)
            .clickable { onChange(mode) }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = ChatText.bodySmall,
            color = colors.textPrimary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check, null,
                tint = colors.textPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
