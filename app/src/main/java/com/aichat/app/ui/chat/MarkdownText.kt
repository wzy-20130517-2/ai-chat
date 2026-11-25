package com.aichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.ChatText
import com.aichat.app.ui.theme.LocalChatColors

/**
 * 轻量 Markdown 渲染。
 *
 * 【为什么不用第三方 markdown 库】
 * 主流库（如 markwon）是为 View 体系写的，Compose 下要么用 AndroidView 包一层
 * （滚动嵌套、选中复制都会出问题），要么找 compose 专用库但配置复杂。
 * 对话场景实际用到的语法很少：代码块、行内代码、粗体斜体、标题、列表。
 * 自己按行解析，几十行就够，且完全可控。
 *
 * 【渲染策略：块级 + 行内两级】
 * 先按行切成块（代码块 / 标题 / 列表项 / 段落），每个块单独渲染；
 * 段落内部再做行内样式（粗体/斜体/代码/链接）。
 * 这样代码块能保留原始换行和缩进，不会被行内解析破坏。
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Code -> CodeBlock(block.code, block.lang, colors)
                is Block.Heading -> Text(
                    text = inline(block.text, color, colors),
                    style = ChatText.body.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 19.sp
                            else -> 17.sp
                        },
                    ),
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "•",
                        style = ChatText.body,
                        color = colors.textTertiary,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = inline(block.text, color, colors),
                        style = ChatText.body,
                        color = color,
                    )
                }
                is Block.Numbered -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${block.number}.",
                        style = ChatText.body,
                        color = colors.textTertiary,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        text = inline(block.text, color, colors),
                        style = ChatText.body,
                        color = color,
                    )
                }
                is Block.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(colors.border),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = inline(block.text, color, colors),
                        style = ChatText.body.copy(fontStyle = FontStyle.Italic),
                        color = colors.textSecondary,
                    )
                }
                is Block.Paragraph -> Text(
                    text = inline(block.text, color, colors),
                    style = ChatText.body,
                    color = color,
                )
                Block.Divider -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.border),
                )
            }
        }
    }
}

/** 代码块：等宽字体 + 深色底 + 横向滚动（长行不折行，保持代码可读）。 */
@Composable
private fun CodeBlock(
    code: String,
    lang: String,
    colors: com.aichat.app.ui.theme.ChatColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg),
    ) {
        if (lang.isNotBlank()) {
            Text(
                text = lang,
                style = ChatText.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = code,
                style = ChatText.mono,
                color = colors.textPrimary,
                softWrap = false,
            )
        }
    }
}

// ───────────────────── 解析 ─────────────────────

private sealed class Block {
    data class Paragraph(val text: String) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Code(val code: String, val lang: String) : Block()
    data class Bullet(val text: String) : Block()
    data class Numbered(val number: Int, val text: String) : Block()
    data class Quote(val text: String) : Block()
    object Divider : Block()
}

/**
 * 把文本切成块。
 *
 * 代码围栏用状态机处理 —— 不能用正则整体匹配 ```` ```...``` ````，
 * 因为流式输出时代码块可能还没闭合，正则匹配不到就会把代码当普通文本解析，
 * 出现满屏星号和井号。
 */
private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.split('\n')
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // 代码围栏
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            var closed = false
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith("```")) {
                    closed = true
                    i++
                    break
                }
                codeLines += lines[i]
                i++
            }
            // 没闭合说明还在流式输出，照常渲染（不要吞掉内容）
            blocks += Block.Code(codeLines.joinToString("\n"), lang)
            if (!closed) {
                // 已到末尾，退出
                return blocks
            }
            continue
        }

        when {
            trimmed.isBlank() -> {
                flushParagraph()
                i++
            }

            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                blocks += Block.Divider
                i++
            }

            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(3)
                val content = trimmed.drop(level).trim()
                if (content.isNotEmpty()) blocks += Block.Heading(level, content)
                i++
            }

            trimmed.startsWith("> ") -> {
                flushParagraph()
                blocks += Block.Quote(trimmed.removePrefix("> ").trim())
                i++
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushParagraph()
                blocks += Block.Bullet(trimmed.drop(2).trim())
                i++
            }

            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                flushParagraph()
                val num = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                blocks += Block.Numbered(num, trimmed.replaceFirst(Regex("^\\d+\\.\\s*"), ""))
                i++
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * 行内样式解析：**粗体**、*斜体*、`代码`、[链接](url)。
 *
 * 用顺序扫描而不是递归正则：正则要处理嵌套和转义，越写越复杂；
 * 这里只需要处理最常见的几种，状态机足够且不会回溯爆炸
 * （之前的项目踩过 Markdown 正则回溯导致卡死）。
 */
private fun inline(
    text: String,
    baseColor: Color,
    colors: com.aichat.app.ui.theme.ChatColors,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val sb = StringBuilder()

    fun flush() {
        if (sb.isNotEmpty()) {
            append(sb.toString())
            sb.setLength(0)
        }
    }

    while (i < text.length) {
        when {
            // 行内代码 `code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.codeBg,
                            fontSize = 14.sp,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }

            // 粗体 **bold**
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = baseColor)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    sb.append(text[i]); i++
                }
            }

            // 斜体 *italic*
            text[i] == '*' && !text.startsWith("**", i) -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }

            // 链接 [text](url) —— 只渲染文字，不真的做点击（终端对话里外链意义不大，
            // 做点击要配 UriHandler 且要防恶意链接，收益不抵成本）
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket > i) text.indexOf('(', closeBracket) else -1
                val closeParen = if (openParen > 0) text.indexOf(')', openParen) else -1
                if (closeBracket > i && openParen == closeBracket + 1 && closeParen > openParen) {
                    flush()
                    append(text.substring(i + 1, closeBracket))
                    i = closeParen + 1
                } else {
                    sb.append(text[i]); i++
                }
            }

            else -> {
                sb.append(text[i]); i++
            }
        }
    }
    flush()
}
