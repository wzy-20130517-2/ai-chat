package com.aichat.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ChatGPT 布局的配色。
 *
 * 关键取向：整体是低饱和中性色，只有强调色（绿）带彩。
 * ChatGPT 的观感来自「大量留白 + 极淡的分隔线 + 单一强调色」，
 * 而不是靠边框和阴影堆出来的层叠感。
 */
data class ChatColors(
    val bgMain: Color,
    val bgSidebar: Color,
    val bgElevated: Color,
    val bgBubbleUser: Color,
    val bgBubbleAi: Color,
    val bgInput: Color,
    val bgInputFocused: Color,
    val bgHover: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
    val accent: Color,
    val accentPressed: Color,
    val danger: Color,
    val codeBg: Color,
    val codeBorder: Color,
)

val LightColors = ChatColors(
    bgMain = Color(0xFFFFFFFF),
    bgSidebar = Color(0xFFF9F9F9),
    bgElevated = Color(0xFFFFFFFF),
    bgBubbleUser = Color(0xFFF4F4F4),
    bgBubbleAi = Color(0x00000000),
    bgInput = Color(0xFFFFFFFF),
    bgInputFocused = Color(0xFFFFFFFF),
    bgHover = Color(0xFFF0F0F0),
    textPrimary = Color(0xFF0D0D0D),
    textSecondary = Color(0xFF5D5D5D),
    textTertiary = Color(0xFF8F8F8F),
    border = Color(0xFFE5E5E5),
    accent = Color(0xFF0D0D0D),
    accentPressed = Color(0xFF2A2A2A),
    danger = Color(0xFFD93025),
    codeBg = Color(0xFFF6F6F6),
    codeBorder = Color(0xFFE5E5E5),
)

val DarkColors = ChatColors(
    bgMain = Color(0xFF212121),
    bgSidebar = Color(0xFF171717),
    bgElevated = Color(0xFF2F2F2F),
    bgBubbleUser = Color(0xFF2F2F2F),
    bgBubbleAi = Color(0x00000000),
    bgInput = Color(0xFF2F2F2F),
    bgInputFocused = Color(0xFF2F2F2F),
    bgHover = Color(0xFF3A3A3A),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFFB4B4B4),
    textTertiary = Color(0xFF8E8E8E),
    border = Color(0xFF3A3A3A),
    accent = Color(0xFFFFFFFF),
    accentPressed = Color(0xFFE0E0E0),
    danger = Color(0xFFF28B82),
    codeBg = Color(0xFF171717),
    codeBorder = Color(0xFF3A3A3A),
)

/** 深色主题下输入框等控件用的品牌绿（ChatGPT 的标志色）。 */
val BrandGreen = Color(0xFF10A37F)
val BrandGreenDark = Color(0xFF0E8F6F)
