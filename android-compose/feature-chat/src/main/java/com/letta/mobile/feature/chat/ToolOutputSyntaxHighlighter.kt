package com.letta.mobile.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.tooloutput.DiffFile
import com.letta.mobile.data.tooloutput.ToolOutputBlock
import com.letta.mobile.data.tooloutput.ToolOutputDocument
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.ui.theme.customColors
import java.util.LinkedHashMap

private const val TOOL_OUTPUT_DOCUMENT_CACHE_ENTRIES = 32
private const val TOOL_OUTPUT_HIGHLIGHT_CACHE_ENTRIES = 128
private const val TOOL_OUTPUT_CACHE_FINGERPRINT_CHARS = 96

private val toolOutputDocumentCache =
    ToolOutputLruCache<ToolOutputContentKey, ToolOutputDocument>(
        TOOL_OUTPUT_DOCUMENT_CACHE_ENTRIES,
    )
private val toolOutputHighlightCache =
    ToolOutputLruCache<ToolOutputHighlightCacheKey, List<ToolOutputHighlightSpan>>(
        TOOL_OUTPUT_HIGHLIGHT_CACHE_ENTRIES,
    )

internal object ToolOutputCaches {
    fun getDocument(key: ToolOutputContentKey): ToolOutputDocument? =
        toolOutputDocumentCache.get(key)

    fun getHighlightSpans(key: ToolOutputHighlightCacheKey): List<ToolOutputHighlightSpan>? =
        toolOutputHighlightCache.get(key)
}

internal data class ToolOutputHighlightCacheKey(
    val content: ToolOutputContentKey,
    val mode: ToolOutputHighlightMode,
    val languageHint: String?,
)

internal enum class ToolOutputHighlightMode {
    None,
    Json,
    Code,
    Shell,
    Log,
    Table,
}

internal enum class ToolOutputHighlightKind {
    Key,
    StringLiteral,
    Number,
    Literal,
    Keyword,
    Function,
    Comment,
    Punctuation,
    Prompt,
    Success,
    Warning,
    Error,
    Header,
}

internal data class ToolOutputHighlightSpan(
    val start: Int,
    val end: Int,
    val kind: ToolOutputHighlightKind,
)

internal data class ToolOutputContentKey(
    val length: Int,
    val hash: Int,
    val prefix: String,
    val suffix: String,
)

internal fun String.toolOutputContentKey(): ToolOutputContentKey =
    ToolOutputContentKey(
        length = length,
        hash = hashCode(),
        prefix = take(TOOL_OUTPUT_CACHE_FINGERPRINT_CHARS),
        suffix = takeLast(TOOL_OUTPUT_CACHE_FINGERPRINT_CHARS),
    )

internal fun cachedToolOutputDocument(raw: String): ToolOutputDocument {
    if (raw.length > ToolOutputDocumentMaxCacheableRawChars) {
        return ToolOutputParser.parse(raw)
    }
    return toolOutputDocumentCache.getOrPut(raw.toolOutputContentKey()) {
        ToolOutputParser.parse(raw)
    }
}

internal fun cachedToolOutputHighlightSpans(
    text: String,
    mode: ToolOutputHighlightMode,
    languageHint: String? = null,
): List<ToolOutputHighlightSpan> =
    toolOutputHighlightCache.getOrPut(
        ToolOutputHighlightCacheKey(
            content = text.toolOutputContentKey(),
            mode = mode,
            languageHint = languageHint,
        )
    ) {
        highlightToolOutputText(text = text, mode = mode, languageHint = languageHint)
    }

internal fun clearToolOutputRenderCachesForTest() {
    toolOutputDocumentCache.clear()
    toolOutputHighlightCache.clear()
}

private class ToolOutputLruCache<K, V>(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val values = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    fun get(key: K): V? = synchronized(lock) {
        values[key]
    }

    fun getOrPut(key: K, producer: () -> V): V {
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
}

internal data class ToolOutputSyntaxColors(
    val default: Color,
    val key: Color,
    val stringLiteral: Color,
    val number: Color,
    val literal: Color,
    val keyword: Color,
    val function: Color,
    val comment: Color,
    val punctuation: Color,
    val prompt: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val header: Color,
)

@Composable
internal fun toolOutputSyntaxColors(isError: Boolean): ToolOutputSyntaxColors {
    val customColors = MaterialTheme.customColors
    val scheme = MaterialTheme.colorScheme
    return ToolOutputSyntaxColors(
        default = if (isError) scheme.error else scheme.onSurfaceVariant,
        key = scheme.primary,
        stringLiteral = scheme.tertiary,
        number = scheme.secondary,
        literal = scheme.primary.copy(alpha = 0.88f),
        keyword = scheme.primary,
        function = scheme.secondary,
        comment = scheme.onSurfaceVariant.copy(alpha = 0.58f),
        punctuation = scheme.onSurfaceVariant.copy(alpha = 0.62f),
        prompt = scheme.primary,
        success = customColors.successColor,
        warning = customColors.warningTextColor,
        error = scheme.error,
        header = scheme.primary.copy(alpha = 0.88f),
    )
}

internal fun highlightToolOutputText(
    text: String,
    mode: ToolOutputHighlightMode,
    languageHint: String? = null,
): List<ToolOutputHighlightSpan> = when (mode) {
    ToolOutputHighlightMode.None -> emptyList()
    ToolOutputHighlightMode.Json -> highlightJsonOutput(text)
    ToolOutputHighlightMode.Code -> highlightCodeOutput(text, languageHint)
    ToolOutputHighlightMode.Shell -> highlightShellOutput(text)
    ToolOutputHighlightMode.Log -> highlightLogOutput(text)
    ToolOutputHighlightMode.Table -> highlightTableOutput(text)
}

internal fun highlightJsonOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    var index = 0
    while (index < text.length && spans.hasHighlightBudget()) {
        when {
            text[index] == '"' -> {
                val end = scanQuotedString(text, index)
                val kind = if (isJsonObjectKey(text, end)) {
                    ToolOutputHighlightKind.Key
                } else {
                    ToolOutputHighlightKind.StringLiteral
                }
                spans.addValid(index, end, kind)
                index = end
            }
            text[index].isNumberStart(text, index) -> {
                val end = scanNumber(text, index)
                spans.addValid(index, end, ToolOutputHighlightKind.Number)
                index = end
            }
            text.startsWithJsonLiteral(index) != null -> {
                val literal = text.startsWithJsonLiteral(index).orEmpty()
                spans.addValid(index, index + literal.length, ToolOutputHighlightKind.Literal)
                index += literal.length
            }
            text[index] in "{}[]:," -> {
                spans.addValid(index, index + 1, ToolOutputHighlightKind.Punctuation)
                index++
            }
            else -> index++
        }
    }
    return spans
}

internal fun highlightCodeOutput(text: String, languageHint: String?): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    val occupied = BooleanArray(text.length)

    fun addTracked(start: Int, end: Int, kind: ToolOutputHighlightKind) {
        if (spans.addValid(start, end, kind)) {
            occupied.mark(start, end)
        }
    }

    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("//", index) -> {
                val end = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                addTracked(index, end, ToolOutputHighlightKind.Comment)
                index = end
            }
            text.startsWith("/*", index) -> {
                val end = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
                addTracked(index, end, ToolOutputHighlightKind.Comment)
                index = end
            }
            text[index] == '#' && languageHint.allowsHashComments() -> {
                val end = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                addTracked(index, end, ToolOutputHighlightKind.Comment)
                index = end
            }
            text[index] == '"' || text[index] == '\'' || text[index] == '`' -> {
                val end = scanQuotedString(text, index)
                addTracked(index, end, ToolOutputHighlightKind.StringLiteral)
                index = end
            }
            else -> index++
        }
    }

    for (match in keywordRegex(languageHint).findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        addIfClear(spans, occupied, match.range.first, match.range.last + 1, ToolOutputHighlightKind.Keyword)
    }
    for (match in codeNumberRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        addIfClear(spans, occupied, match.range.first, match.range.last + 1, ToolOutputHighlightKind.Number)
    }
    for (match in codeFunctionRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        addIfClear(spans, occupied, match.range.first, match.range.last + 1, ToolOutputHighlightKind.Function)
    }
    for (position in text.indices) {
        if (!spans.hasHighlightBudget()) break
        val char = text[position]
        if (char in codePunctuation && !occupied[position]) {
            spans.addValid(position, position + 1, ToolOutputHighlightKind.Punctuation)
        }
    }
    return spans
}

internal fun highlightShellOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = highlightLogOutput(text).toMutableList()
    var lineStart = 0
    for (line in text.split('\n')) {
        if (!spans.hasHighlightBudget()) break
        val promptLength = when {
            line.startsWith("$ ") -> 2
            line.startsWith("> ") -> 2
            line.startsWith("PS> ") -> 4
            else -> 0
        }
        if (promptLength > 0) {
            spans.addValid(lineStart, lineStart + promptLength, ToolOutputHighlightKind.Prompt)
            val commandStart = lineStart + promptLength
            val commandEnd = findShellCommandEnd(text, commandStart, lineStart + line.length)
            spans.addValid(commandStart, commandEnd, ToolOutputHighlightKind.Keyword)
            for (match in shellFlagRegex.findAll(line)) {
                if (!spans.hasHighlightBudget()) break
                val start = lineStart + match.range.first
                val end = lineStart + match.range.last + 1
                if (start >= commandEnd) {
                    spans.addValid(start, end, ToolOutputHighlightKind.Literal)
                }
            }
        }
        lineStart += line.length + 1
    }
    return spans
}

internal fun highlightLogOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    for (match in errorWordRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Error)
    }
    for (match in warningWordRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Warning)
    }
    for (match in successWordRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Success)
    }
    for (match in codeNumberRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Number)
    }
    return spans
}

internal fun highlightTableOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    val headerEnd = text.indexOf('\n').takeIf { it >= 0 } ?: text.length
    spans.addValid(0, headerEnd, ToolOutputHighlightKind.Header)
    for (match in codeNumberRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Number)
    }
    return spans
}

internal fun MutableList<ToolOutputHighlightSpan>.addValid(
    start: Int,
    end: Int,
    kind: ToolOutputHighlightKind,
): Boolean {
    if (start < 0 || end <= start) return false
    if (!hasHighlightBudget()) return false
    add(ToolOutputHighlightSpan(start = start, end = end, kind = kind))
    return true
}

internal fun List<ToolOutputHighlightSpan>.hasHighlightBudget(): Boolean =
    size < ToolOutputMaxHighlightSpans

internal fun addIfClear(
    spans: MutableList<ToolOutputHighlightSpan>,
    occupied: BooleanArray,
    start: Int,
    end: Int,
    kind: ToolOutputHighlightKind,
) {
    if (start < 0 || end > occupied.size || occupied.anyInRange(start, end)) return
    if (spans.addValid(start, end, kind)) {
        occupied.mark(start, end)
    }
}

internal fun BooleanArray.anyInRange(start: Int, end: Int): Boolean =
    (start.coerceAtLeast(0) until end.coerceAtMost(size)).any { this[it] }

internal fun BooleanArray.mark(start: Int, end: Int) {
    (start.coerceAtLeast(0) until end.coerceAtMost(size)).forEach { this[it] = true }
}

internal fun scanQuotedString(text: String, start: Int): Int {
    val quote = text[start]
    var index = start + 1
    var escaped = false
    while (index < text.length) {
        val char = text[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == quote -> return index + 1
        }
        index++
    }
    return text.length
}

internal fun isJsonObjectKey(text: String, stringEnd: Int): Boolean {
    var index = stringEnd
    while (index < text.length && text[index].isWhitespace()) {
        index++
    }
    return index < text.length && text[index] == ':'
}

internal fun Char.isNumberStart(text: String, index: Int): Boolean =
    isDigit() || (this == '-' && text.charOrNull(index + 1)?.isDigit() == true)

internal fun scanNumber(text: String, start: Int): Int {
    var index = start
    if (text.charOrNull(index) == '-') index++
    while (text.charOrNull(index)?.isDigit() == true) index++
    if (text.charOrNull(index) == '.') {
        index++
        while (text.charOrNull(index)?.isDigit() == true) index++
    }
    if (text.charOrNull(index) == 'e' || text.charOrNull(index) == 'E') {
        index++
        if (text.charOrNull(index) == '+' || text.charOrNull(index) == '-') index++
        while (text.charOrNull(index)?.isDigit() == true) index++
    }
    return index
}

internal fun String.startsWithJsonLiteral(index: Int): String? =
    jsonLiterals.firstOrNull { literal ->
        startsWith(literal, startIndex = index) &&
            charOrNull(index + literal.length)?.let { it.isLetterOrDigit() || it == '_' } != true
    }

internal fun String.charOrNull(index: Int): Char? =
    if (index in indices) this[index] else null

internal fun String?.allowsHashComments(): Boolean =
    this == "bash" || this == "python" || this == "shell" || this == "sh"

internal fun findShellCommandEnd(text: String, start: Int, lineEnd: Int): Int {
    var index = start
    while (index < lineEnd && !text[index].isWhitespace()) {
        index++
    }
    return index
}

internal fun keywordRegex(languageHint: String?): Regex {
    val keywords = when (languageHint) {
        "kotlin" -> kotlinKeywords
        "python" -> pythonKeywords
        "javascript" -> javascriptKeywords
        "bash", "sh", "shell" -> shellKeywords
        else -> commonCodeKeywords
    }
    return Regex("""\b(${keywords.joinToString("|") { Regex.escape(it) }})\b""")
}

private val jsonLiterals = listOf("true", "false", "null")
private val codePunctuation = setOf('{', '}', '[', ']', '(', ')', '.', ',', ':', ';', '=', '+', '-', '*', '/', '<', '>')
private val codeNumberRegex = Regex("""\b\d+(?:\.\d+)?\b""")
private val codeFunctionRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*(?=\s*\()""")
private val shellFlagRegex = Regex("""(?<=\s)-{1,2}[A-Za-z0-9][A-Za-z0-9_-]*""")
private val errorWordRegex = Regex("""\b(ERROR|ERR|FAILED|FAILURE|FAIL|FATAL|EXCEPTION|DENIED|CRASHED)\b""", RegexOption.IGNORE_CASE)
private val warningWordRegex = Regex("""\b(WARN|WARNING|CAUTION|SKIPPED)\b""", RegexOption.IGNORE_CASE)
private val successWordRegex = Regex("""\b(OK|SUCCESS|PASSED|DONE|CLEAN|COMPLETE|COMPLETED)\b""", RegexOption.IGNORE_CASE)
private val commonCodeKeywords = setOf(
    "as",
    "async",
    "await",
    "break",
    "catch",
    "class",
    "const",
    "continue",
    "def",
    "else",
    "false",
    "for",
    "fun",
    "function",
    "if",
    "import",
    "in",
    "let",
    "new",
    "null",
    "object",
    "package",
    "return",
    "throw",
    "true",
    "try",
    "val",
    "var",
    "when",
    "while",
)
private val kotlinKeywords = commonCodeKeywords + setOf(
    "data",
    "internal",
    "is",
    "override",
    "private",
    "sealed",
    "suspend",
)
private val pythonKeywords = commonCodeKeywords + setOf(
    "elif",
    "except",
    "finally",
    "from",
    "None",
    "pass",
    "raise",
    "with",
    "yield",
)
private val javascriptKeywords = commonCodeKeywords + setOf(
    "export",
    "extends",
    "interface",
    "this",
    "type",
)
private val shellKeywords = setOf(
    "cd",
    "cat",
    "cp",
    "echo",
    "find",
    "git",
    "grep",
    "ls",
    "mkdir",
    "mv",
    "npm",
    "pnpm",
    "rm",
    "rg",
    "sed",
    "yarn",
)

internal data class LimitedText(
    val text: String,
    val omittedLines: Int,
    val omittedChars: Int,
)

internal data class LimitedDiffFiles(
    val files: List<DiffFile>,
    val omittedLines: Int,
)

internal fun limitDiffFilesForRendering(
    files: List<DiffFile>,
    maxLines: Int = ToolOutputMaxRenderedLines,
): LimitedDiffFiles {
    if (maxLines <= 0) {
        return LimitedDiffFiles(files = emptyList(), omittedLines = files.sumOf { it.lines.size })
    }

    val limitedFiles = mutableListOf<DiffFile>()
    var usedLines = 0
    var omittedLines = 0
    files.forEach { file ->
        val remaining = maxLines - usedLines
        if (remaining <= 0) {
            omittedLines += file.lines.size
        } else {
            val visibleLines = file.lines.take(remaining)
            if (visibleLines.isNotEmpty()) {
                limitedFiles += file.copy(lines = visibleLines)
            }
            usedLines += visibleLines.size
            omittedLines += file.lines.size - visibleLines.size
        }
    }
    return LimitedDiffFiles(files = limitedFiles, omittedLines = omittedLines)
}

internal fun limitRenderedText(
    text: String,
    maxLines: Int = ToolOutputMaxRenderedLines,
    maxChars: Int = ToolOutputMaxRenderedChars,
): LimitedText {
    val lines = text.lines()
    val safeMaxLines = maxLines.coerceAtLeast(0)
    val safeMaxChars = maxChars.coerceAtLeast(0)
    val omittedLines = (lines.size - safeMaxLines).coerceAtLeast(0)
    val byLine = lines.take(safeMaxLines).joinToString("\n")
    val rendered = byLine.take(safeMaxChars)
    val omittedChars = (byLine.length - rendered.length).coerceAtLeast(0)
    return LimitedText(text = rendered, omittedLines = omittedLines, omittedChars = omittedChars)
}

internal fun formatTable(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val width = rows.maxOf { it.size }
    val columnWidths = (0 until width).map { column ->
        rows.maxOf { row -> row.getOrNull(column)?.length ?: 0 }
    }
    return rows.joinToString("\n") { row ->
        (0 until width).joinToString("  ") { column ->
            row.getOrNull(column).orEmpty().padEnd(columnWidths[column])
        }.trimEnd()
    }
}

internal fun ToolOutputBlock.previewText(): String = when (this) {
    is ToolOutputBlock.Json -> pretty
    is ToolOutputBlock.Diff -> raw
    is ToolOutputBlock.StackTrace -> headline
    is ToolOutputBlock.ShellTranscript -> if (output.isNotBlank()) output else commandLines.joinToString("\n")
    is ToolOutputBlock.AnsiLog -> stripped
    is ToolOutputBlock.Table -> formatTable(rows)
    is ToolOutputBlock.CodeLike -> raw
    is ToolOutputBlock.PlainText -> raw
}

internal fun ToolOutputBlock.highlightMode(): ToolOutputHighlightMode = when (this) {
    is ToolOutputBlock.Json -> ToolOutputHighlightMode.Json
    is ToolOutputBlock.Diff -> ToolOutputHighlightMode.None
    is ToolOutputBlock.StackTrace -> ToolOutputHighlightMode.Log
    is ToolOutputBlock.ShellTranscript -> ToolOutputHighlightMode.Shell
    is ToolOutputBlock.AnsiLog -> ToolOutputHighlightMode.Log
    is ToolOutputBlock.Table -> ToolOutputHighlightMode.Table
    is ToolOutputBlock.CodeLike -> ToolOutputHighlightMode.Code
    is ToolOutputBlock.PlainText -> ToolOutputHighlightMode.Log
}
