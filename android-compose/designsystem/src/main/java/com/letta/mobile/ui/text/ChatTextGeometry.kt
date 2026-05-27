package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.floor

private const val CONTENT_FINGERPRINT_CHARS = 96
private const val DEFAULT_TEXT_GEOMETRY_CACHE_ENTRIES = 160
private const val FALLBACK_TEXT_SIZE_SP = 14f
private const val MONOSPACE_CHAR_WIDTH_EM = 0.62f

enum class ChatTextLayoutMode {
    Plain,
    PreWrap,
    Code,
    MarkdownParagraph,
}

data class ChatTextContentKey(
    val length: Int,
    val hash: Int,
    val prefix: String,
    val suffix: String,
)

fun String.chatTextContentKey(): ChatTextContentKey =
    ChatTextContentKey(
        length = length,
        hash = hashCode(),
        prefix = take(CONTENT_FINGERPRINT_CHARS),
        suffix = takeLast(CONTENT_FINGERPRINT_CHARS),
    )

data class ChatTextGeometryKey(
    val content: ChatTextContentKey,
    val widthPx: Int,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection,
    val styleFingerprint: Int,
    val mode: ChatTextLayoutMode,
    val softWrap: Boolean,
    val maxLines: Int,
)

class ChatTextGeometry(
    val lineCount: Int,
    val heightPx: Int,
    val maxLineWidthPx: Int,
    private val visibleLineEndOffsets: IntArray,
) {
    fun visibleEndForLine(lineIndex: Int): Int? =
        visibleLineEndOffsets.getOrNull(lineIndex)
}

data class ChatTextVisualClip(
    val text: String,
    val omittedLines: Int,
    val omittedChars: Int,
    val geometry: ChatTextGeometry?,
)

class ChatTextGeometryCache(
    private val maxEntries: Int = DEFAULT_TEXT_GEOMETRY_CACHE_ENTRIES,
) {
    private val lock = Any()
    private val values = object : LinkedHashMap<ChatTextGeometryKey, ChatTextGeometry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatTextGeometryKey, ChatTextGeometry>?): Boolean =
            size > maxEntries
    }

    fun get(key: ChatTextGeometryKey): ChatTextGeometry? = synchronized(lock) {
        values[key]
    }

    fun getOrPut(key: ChatTextGeometryKey, producer: () -> ChatTextGeometry): ChatTextGeometry {
        get(key)?.let { return it }
        val produced = producer()
        return synchronized(lock) {
            values[key] ?: produced.also { values[key] = it }
        }
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
        }
    }

    fun size(): Int = synchronized(lock) {
        values.size
    }
}

class ChatTextGeometryMeasurer(
    private val textMeasurer: TextMeasurer,
    private val cache: ChatTextGeometryCache = ChatTextGeometryCache(),
) {
    fun measure(
        text: String,
        style: TextStyle,
        widthPx: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        mode: ChatTextLayoutMode,
        softWrap: Boolean = true,
        maxLines: Int = Int.MAX_VALUE,
    ): ChatTextGeometry {
        val safeWidthPx = widthPx.coerceAtLeast(0)
        val safeMaxLines = maxLines.coerceAtLeast(1)
        val key = ChatTextGeometryKey(
            content = text.chatTextContentKey(),
            widthPx = safeWidthPx,
            density = density.density,
            fontScale = density.fontScale,
            layoutDirection = layoutDirection,
            styleFingerprint = style.hashCode(),
            mode = mode,
            softWrap = softWrap,
            maxLines = safeMaxLines,
        )
        return cache.getOrPut(key) {
            val result = textMeasurer.measure(
                text = AnnotatedString(text),
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = softWrap,
                maxLines = safeMaxLines,
                constraints = Constraints(maxWidth = safeWidthPx),
                layoutDirection = layoutDirection,
                density = density,
            )
            result.toChatTextGeometry()
        }
    }

    fun clipToVisualLines(
        text: String,
        style: TextStyle,
        widthPx: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        mode: ChatTextLayoutMode,
        maxLines: Int,
        maxChars: Int,
    ): ChatTextVisualClip {
        val safeMaxLines = maxLines.coerceAtLeast(0)
        val safeMaxChars = maxChars.coerceAtLeast(0)
        if (safeMaxLines == 0 || safeMaxChars == 0) {
            return ChatTextVisualClip(
                text = "",
                omittedLines = estimateLogicalLineCount(text),
                omittedChars = text.length,
                geometry = null,
            )
        }

        val charLimited = text.take(safeMaxChars)
        val omittedByCharLimit = (text.length - charLimited.length).coerceAtLeast(0)
        if (widthPx <= 0 || charLimited.isEmpty()) {
            return ChatTextVisualClip(
                text = charLimited,
                omittedLines = 0,
                omittedChars = omittedByCharLimit,
                geometry = null,
            )
        }

        val geometry = measure(
            text = charLimited,
            style = style,
            widthPx = widthPx,
            density = density,
            layoutDirection = layoutDirection,
            mode = mode,
            maxLines = Int.MAX_VALUE,
        )
        if (geometry.lineCount <= safeMaxLines) {
            val estimatedCodeClip = if (mode == ChatTextLayoutMode.Code) {
                estimateCodeVisualClip(
                    text = charLimited,
                    style = style,
                    widthPx = widthPx,
                    density = density,
                    maxLines = safeMaxLines,
                )
            } else {
                null
            }
            if (estimatedCodeClip != null) {
                val clipped = charLimited.take(estimatedCodeClip.endOffset).trimEnd()
                return ChatTextVisualClip(
                    text = clipped,
                    omittedLines = estimatedCodeClip.omittedLines,
                    omittedChars = (charLimited.length - clipped.length + omittedByCharLimit).coerceAtLeast(0),
                    geometry = geometry,
                )
            }
            return ChatTextVisualClip(
                text = charLimited,
                omittedLines = 0,
                omittedChars = omittedByCharLimit,
                geometry = geometry,
            )
        }

        val endOffset = geometry.visibleEndForLine(safeMaxLines - 1)
            ?.coerceIn(0, charLimited.length)
            ?: charLimited.length
        val clipped = charLimited.take(endOffset).trimEnd()
        return ChatTextVisualClip(
            text = clipped,
            omittedLines = geometry.lineCount - safeMaxLines,
            omittedChars = (charLimited.length - clipped.length + omittedByCharLimit).coerceAtLeast(0),
            geometry = geometry,
        )
    }
}

@Composable
fun rememberChatTextGeometryMeasurer(
    maxEntries: Int = DEFAULT_TEXT_GEOMETRY_CACHE_ENTRIES,
): ChatTextGeometryMeasurer {
    val textMeasurer = rememberTextMeasurer()
    return remember(textMeasurer, maxEntries) {
        ChatTextGeometryMeasurer(
            textMeasurer = textMeasurer,
            cache = ChatTextGeometryCache(maxEntries = maxEntries),
        )
    }
}

private fun TextLayoutResult.toChatTextGeometry(): ChatTextGeometry {
    val lineEnds = IntArray(lineCount) { lineIndex ->
        getLineEnd(lineIndex, visibleEnd = true)
    }
    val maxLineWidthPx = (0 until lineCount).maxOfOrNull { lineIndex ->
        ceil(getLineRight(lineIndex) - getLineLeft(lineIndex)).toInt()
    } ?: 0
    return ChatTextGeometry(
        lineCount = lineCount,
        heightPx = size.height,
        maxLineWidthPx = maxLineWidthPx,
        visibleLineEndOffsets = lineEnds,
    )
}

private fun estimateLogicalLineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

private data class EstimatedCodeClip(
    val endOffset: Int,
    val omittedLines: Int,
)

private fun estimateCodeVisualClip(
    text: String,
    style: TextStyle,
    widthPx: Int,
    density: Density,
    maxLines: Int,
): EstimatedCodeClip? {
    if (text.isEmpty() || maxLines <= 0 || widthPx <= 0) return null
    val fontSizePx = if (style.fontSize.isSpecified) {
        with(density) { style.fontSize.toPx() }
    } else {
        FALLBACK_TEXT_SIZE_SP * density.density * density.fontScale
    }
    val estimatedCharWidthPx = (fontSizePx * MONOSPACE_CHAR_WIDTH_EM).coerceAtLeast(1f)
    val maxCharsPerLine = floor(widthPx / estimatedCharWidthPx).toInt().coerceAtLeast(1)

    var visualLines = 1
    var column = 0
    var overflowIndex: Int? = null
    for (index in text.indices) {
        val char = text[index]
        if (char == '\n') {
            column = 0
            if (index < text.lastIndex) {
                visualLines++
                if (visualLines > maxLines && overflowIndex == null) {
                    overflowIndex = index
                }
            }
        } else {
            column++
            if (column >= maxCharsPerLine && index < text.lastIndex) {
                column = 0
                visualLines++
                if (visualLines > maxLines && overflowIndex == null) {
                    overflowIndex = index + 1
                }
            }
        }
    }
    if (visualLines <= maxLines) return null
    return EstimatedCodeClip(
        endOffset = overflowIndex ?: text.length,
        omittedLines = visualLines - maxLines,
    )
}
