package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Markdown 渲染组件（基于 mikepenz multiplatform-markdown-renderer）：
 * 完整 GFM 支持（标题、粗体、行内代码、代码块、列表、引用、表格、分隔线），
 * 代码块带 Highlights 语法高亮（含 JSON）。
 * 配色取自 Miuix 主题，不依赖 MaterialTheme。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val colors = DefaultMarkdownColors(
        text = scheme.onSurfaceVariantSummary,
        codeBackground = scheme.surfaceContainerHighest,
        inlineCodeBackground = scheme.surfaceContainerHighest,
        dividerColor = scheme.dividerLine,
        tableBackground = scheme.surfaceContainerHighest.copy(alpha = 0.5f),
    )
    val typography = rememberMarkdownTypography()
    Markdown(
        content = markdown,
        colors = colors,
        typography = typography,
        modifier = modifier,
        components = markdownComponents(
            codeFence = highlightedCodeFence,
            codeBlock = highlightedCodeBlock,
        )
    )
}

@Composable
private fun rememberMarkdownTypography(): DefaultMarkdownTypography {
    val scheme = MiuixTheme.colorScheme
    return remember {
        val plain = TextStyle(
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = scheme.onSurfaceVariantSummary
        )
        val code = TextStyle(
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface
        )
        DefaultMarkdownTypography(
            h1 = plain.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface),
            h2 = plain.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface),
            h3 = plain.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface),
            h4 = plain.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface),
            h5 = plain.copy(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface),
            h6 = plain.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface),
            text = plain,
            code = code,
            inlineCode = code.copy(
                fontSize = 13.sp,
                color = scheme.primary
            ),
            quote = plain.copy(
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = scheme.onSurfaceVariantSummary
            ),
            paragraph = plain,
            ordered = plain.copy(fontWeight = FontWeight.Bold, color = scheme.primary),
            bullet = plain.copy(fontWeight = FontWeight.Bold, color = scheme.primary),
            list = plain,
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = scheme.primary,
                    textDecoration = TextDecoration.Underline
                )
            ),
            table = plain.copy(fontSize = 13.sp, lineHeight = 19.sp)
        )
    }
}