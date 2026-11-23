package com.example.ssh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.NocAmber
import com.example.ui.theme.NocCrimson
import com.example.ui.theme.NocCyan
import com.example.ui.theme.NocEmerald
import com.example.ui.theme.NocTextPrimary
import com.example.ui.theme.NocTextSecondary

/**
 * Lightweight streaming ANSI terminal buffer that strips non-SGR control codes
 * (title sequences, cursor movement, clear codes) and preserves SGR foreground colors
 * for crisp monospace rendering in Compose.
 */
class AnsiTerminalBuffer(private val maxChars: Int = 32_000) {
    private val rawBuffer = StringBuilder()
    private val pendingEscape = StringBuilder()
    private var pendingCr = false

    fun appendChunk(chunk: String): AnnotatedString {
        if (chunk.contains("\u001B[2J") || chunk.contains("\u001B[H\u001B[2J")) {
            val idx = chunk.lastIndexOf("\u001B[2J")
            rawBuffer.clear()
            pendingEscape.clear()
            pendingCr = false
            processCharacters(chunk.substring(idx + 4))
        } else {
            processCharacters(chunk)
        }
        if (rawBuffer.length > maxChars) {
            val trimAt = rawBuffer.indexOf('\n', rawBuffer.length - maxChars)
            if (trimAt > 0) {
                rawBuffer.delete(0, trimAt + 1)
            } else {
                rawBuffer.delete(0, rawBuffer.length - maxChars)
            }
        }
        return toAnnotatedString()
    }

    fun appendChunkLines(chunk: String): List<AnnotatedString> {
        val full = appendChunk(chunk)
        return splitAnnotatedLines(full)
    }

    fun clear(): AnnotatedString {
        rawBuffer.clear()
        pendingEscape.clear()
        pendingCr = false
        return AnnotatedString("")
    }

    private fun splitAnnotatedLines(source: AnnotatedString): List<AnnotatedString> {
        if (source.isEmpty()) return emptyList()
        val lines = mutableListOf<AnnotatedString>()
        var start = 0
        val text = source.text
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                lines.add(source.subSequence(start, text.length))
                break
            } else {
                lines.add(source.subSequence(start, nl))
                start = nl + 1
            }
        }
        return lines
    }

    private fun clearCurrentLine() {
        val lastNl = rawBuffer.lastIndexOf('\n')
        if (lastNl >= 0) {
            rawBuffer.delete(lastNl + 1, rawBuffer.length)
        } else {
            rawBuffer.clear()
        }
    }

    private fun processCharacters(incoming: String) {
        val text = if (pendingEscape.isNotEmpty()) {
            val combined = pendingEscape.toString() + incoming
            pendingEscape.clear()
            combined
        } else {
            incoming
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (pendingCr) {
                pendingCr = false
                if (ch != '\n') {
                    clearCurrentLine()
                }
            }
            when {
                ch == '\r' -> {
                    if (i + 1 < text.length) {
                        if (text[i + 1] != '\n') {
                            clearCurrentLine()
                        }
                    } else {
                        pendingCr = true
                    }
                }
                ch == '\b' -> {
                    if (rawBuffer.isNotEmpty() && rawBuffer.last() != '\n') {
                        rawBuffer.deleteCharAt(rawBuffer.length - 1)
                    }
                }
                ch == '\u0007' -> {
                    // Bell character ignored
                }
                ch == '\u001B' -> {
                    if (i + 1 >= text.length) {
                        pendingEscape.append(ch)
                        break
                    }
                    when (text[i + 1]) {
                        ']' -> {
                            // OSC sequence terminated by BEL (\u0007) or ST (\u001B\\)
                            val belIdx = text.indexOf('\u0007', i + 2)
                            val stIdx = text.indexOf("\u001B\\", i + 2)
                            val endIdx = when {
                                belIdx >= 0 && stIdx >= 0 -> minOf(belIdx, stIdx + 1)
                                belIdx >= 0 -> belIdx
                                stIdx >= 0 -> stIdx + 1
                                else -> -1
                            }
                            if (endIdx < 0) {
                                if (text.length - i <= 512) {
                                    pendingEscape.append(text, i, text.length)
                                }
                                break
                            }
                            i = endIdx
                        }
                        '[' -> {
                            var j = i + 2
                            while (j < text.length && text[j].code !in 0x40..0x7E) {
                                j++
                            }
                            if (j >= text.length) {
                                if (text.length - i <= 128) {
                                    pendingEscape.append(text, i, text.length)
                                }
                                break
                            }
                            if (text[j] == 'm') {
                                rawBuffer.append(text, i, j + 1)
                            }
                            i = j
                        }
                        else -> {
                            // 2-byte ESC sequence (e.g. ESC =, ESC >)
                            i += 1
                        }
                    }
                }
                else -> rawBuffer.append(ch)
            }
            i++
        }
    }

    private fun toAnnotatedString(): AnnotatedString {
        val snapshot = rawBuffer.toString()
        return buildAnnotatedString {
            var currentColor = NocTextPrimary
            var isBold = false
            var idx = 0

            while (idx < snapshot.length) {
                val escIdx = snapshot.indexOf("\u001B[", idx)
                if (escIdx < 0) {
                    val segment = snapshot.substring(idx)
                    if (segment.isNotEmpty()) {
                        pushStyle(
                            SpanStyle(
                                color = currentColor,
                                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                        append(segment)
                        pop()
                    }
                    break
                }

                if (escIdx > idx) {
                    pushStyle(
                        SpanStyle(
                            color = currentColor,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    append(snapshot.substring(idx, escIdx))
                    pop()
                }

                val mIdx = snapshot.indexOf('m', escIdx + 2)
                if (mIdx < 0) {
                    idx = escIdx + 2
                    continue
                }
                val codesStr = snapshot.substring(escIdx + 2, mIdx)
                val codes = if (codesStr.isEmpty()) listOf(0) else codesStr.split(';').mapNotNull { it.toIntOrNull() }
                for (code in codes) {
                    when (code) {
                        0 -> {
                            currentColor = NocTextPrimary
                            isBold = false
                        }
                        1 -> isBold = true
                        22 -> isBold = false
                        30 -> currentColor = NocTextSecondary
                        31, 91 -> currentColor = NocCrimson
                        32, 92 -> currentColor = NocEmerald
                        33, 93 -> currentColor = NocAmber
                        34, 94 -> currentColor = Color(0xFF60A5FA)
                        35, 95 -> currentColor = Color(0xFFC084FC)
                        36, 96 -> currentColor = NocCyan
                        37, 97 -> currentColor = NocTextPrimary
                        39 -> currentColor = NocTextPrimary
                    }
                }
                idx = mIdx + 1
            }
        }
    }
}
