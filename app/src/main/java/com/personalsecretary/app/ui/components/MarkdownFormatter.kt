package com.personalsecretary.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.TextPrimary

/**
 * Lightweight Compose Markdown renderer tailored for Gemma 4 executive briefings and advisor chat.
 * Handles headings (# / ## / ###), bullet lists (* / -), **bold**, *italic*, and `inline code`
 * cleanly—including partial/unclosed tokens during live token streaming.
 */
fun formatExecutiveMarkdown(
    raw: String,
    boldColor: Color = TextPrimary,
    headingColor: Color = AccentGold
): AnnotatedString {
    val normalized = raw.replace("\r\n", "\n")
    val lines = normalized.split('\n')

    return buildAnnotatedString {
        lines.forEachIndexed { lineIdx, rawLine ->
            val trimmedStart = rawLine.trimStart()
            val leadingSpaces = rawLine.length - trimmedStart.length

            when {
                trimmedStart.startsWith("### ") || trimmedStart.startsWith("## ") || trimmedStart.startsWith("# ") -> {
                    val headingText = trimmedStart.dropWhile { it == '#' }.trimStart()
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = headingColor)) {
                        appendInlineMarkdown(headingText, boldColor = headingColor, codeColor = headingColor)
                    }
                }
                trimmedStart.startsWith("* ") || trimmedStart.startsWith("- ") -> {
                    val indent = if (leadingSpaces >= 2) "    " else "  "
                    append("${indent}• ")
                    appendInlineMarkdown(trimmedStart.drop(2), boldColor = boldColor, codeColor = headingColor)
                }
                trimmedStart == "---" || trimmedStart == "***" -> {
                    append("──────────")
                }
                else -> {
                    appendInlineMarkdown(rawLine, boldColor = boldColor, codeColor = headingColor)
                }
            }

            if (lineIdx < lines.lastIndex) {
                append('\n')
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    line: String,
    boldColor: Color,
    codeColor: Color
) {
    var i = 0
    val n = line.length
    while (i < n) {
        // Bold: **text**
        if (i + 1 < n && line[i] == '*' && line[i + 1] == '*') {
            val end = line.indexOf("**", startIndex = i + 2)
            if (end != -1) {
                val inner = line.substring(i + 2, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) {
                    append(inner)
                }
                i = end + 2
                continue
            } else {
                // Unclosed ** during streaming — render remaining cleanly as bold without showing raw asterisks
                val inner = line.substring(i + 2)
                if (inner.isNotEmpty()) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) {
                        append(inner)
                    }
                }
                break
            }
        }

        // Inline code: `text`
        if (line[i] == '`') {
            val end = line.indexOf('`', startIndex = i + 1)
            if (end != -1) {
                val inner = line.substring(i + 1, end)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) {
                    append(inner)
                }
                i = end + 1
                continue
            }
        }

        // Italic: *text* (only if non-space immediately follows opening *)
        if (line[i] == '*' && i + 1 < n && !line[i + 1].isWhitespace() && line[i + 1] != '*') {
            val end = line.indexOf('*', startIndex = i + 1)
            if (end != -1 && end > i + 1 && !line[end - 1].isWhitespace()) {
                val inner = line.substring(i + 1, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inner)
                }
                i = end + 1
                continue
            }
        }

        append(line[i])
        i++
    }
}
