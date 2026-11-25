package com.aichat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aichat.app.core.data.ThemeMode

val LocalChatColors = staticCompositionLocalOf { LightColors }

/** 语义化字号：ChatGPT 正文偏大（16sp）以保证手机上长段落可读。 */
object ChatText {
    val title = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun AIChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors

    // Material3 只用来托底（TextField/Button 的默认值），真正的配色走 LocalChatColors。
    val scheme = if (dark) {
        darkColorScheme(
            primary = BrandGreen,
            background = colors.bgMain,
            surface = colors.bgElevated,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = BrandGreen,
            background = colors.bgMain,
            surface = colors.bgElevated,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
        )
    }

    CompositionLocalProvider(LocalChatColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
