package com.letta.mobile.data.tooloutput

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ToolOutputDocument(
    val raw: String,
    val blocks: List<ToolOutputBlock>,
    val isTruncated: Boolean = false,
    val omittedCharCount: Int = 0,
)

sealed interface ToolOutputBlock {
    val raw: String

    data class Json(
        override val raw: String,
        val pretty: String,
        val rootKind: JsonRootKind,
    ) : ToolOutputBlock

    data class Diff(
        override val raw: String,
        val files: List<DiffFile>,
    ) : ToolOutputBlock

    data class StackTrace(
        override val raw: String,
        val languageHint: String,
        val headline: String,
        val frames: List<StackFrame>,
    ) : ToolOutputBlock

    data class ShellTranscript(
        override val raw: String,
        val commandLines: List<String>,
        val output: String,
    ) : ToolOutputBlock

    data class AnsiLog(
        override val raw: String,
        val stripped: String,
    ) : ToolOutputBlock

    data class Table(
        override val raw: String,
        val rows: List<List<String>>,
    ) : ToolOutputBlock

    data class CodeLike(
        override val raw: String,
        val languageHint: String?,
    ) : ToolOutputBlock

    data class PlainText(
        override val raw: String,
    ) : ToolOutputBlock
}

enum class JsonRootKind {
    Object,
    Array,
    Primitive,
}

data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val lines: List<DiffLine>,
)

data class DiffLine(
    val type: DiffLineType,
    val text: String,
)

enum class DiffLineType {
    Header,
    Hunk,
    Added,
    Removed,
    Context,
}

data class StackFrame(
    val text: String,
    val file: String? = null,
    val line: Int? = null,
    val symbol: String? = null,
)

interface ToolSyntaxHighlighter {
    fun highlight(languageHint: String?, source: String): List<SyntaxSpan>
}

data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val kind: SyntaxSpanKind,
)

enum class SyntaxSpanKind {
    Keyword,
    StringLiteral,
    Number,
    Comment,
    Type,
    Function,
    Punctuation,
}

object NoOpToolSyntaxHighlighter : ToolSyntaxHighlighter {
    override fun highlight(languageHint: String?, source: String): List<SyntaxSpan> = emptyList()
}

object ToolOutputParser {
    const val MaxAnalyzedChars = 100_000

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        isLenient = false
    }
    private val ansiRegex = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
    private val jvmFrameRegex = Regex("""^\s*at\s+(.+)\(([^:()]+):(\d+)\)\s*$""")
    private val pythonFrameRegex = Regex("""^\s*File "([^"]+)", line (\d+), in (.+)\s*$""")
    private val jsFrameRegex = Regex("""^\s*at\s+(.+?)\s+\((.+):(\d+):(\d+)\)\s*$""")
    private val tableSeparatorCellRegex = Regex(""":?-{3,}:?""")

    fun parse(raw: String): ToolOutputDocument {
        if (raw.isBlank()) {
            return ToolOutputDocument(raw = raw, blocks = listOf(ToolOutputBlock.PlainText(raw)))
        }

        val analyzed = raw.take(MaxAnalyzedChars)
        val isTruncated = raw.length > analyzed.length
        val normalized = analyzed.normalizeLineEndings()
        val hasAnsi = ansiRegex.containsMatchIn(normalized)
        val stripped = stripAnsi(normalized)

        val block = parseJson(stripped)
            ?: parseDiff(stripped)
            ?: parseStackTrace(stripped)
            ?: parseShellTranscript(stripped)
            ?: parseTable(stripped)
            ?: normalized.takeIf { hasAnsi }?.let {
                ToolOutputBlock.AnsiLog(raw = it, stripped = stripped)
            }
            ?: parseCodeLike(stripped)
            ?: ToolOutputBlock.PlainText(stripped)

        return ToolOutputDocument(
            raw = raw,
            blocks = listOf(block),
            isTruncated = isTruncated,
            omittedCharCount = raw.length - analyzed.length,
        )
    }

    fun stripAnsi(text: String): String = ansiRegex.replace(text, "")

    fun sanitizeResultFieldText(text: String): String = text.stripResultBlankWhitespace()

    private fun parseJson(text: String): ToolOutputBlock.Json? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return try {
            val element = normalizeJsonResultFields(json.parseToJsonElement(trimmed))
            val rootKind = when (element) {
                is JsonObject -> JsonRootKind.Object
                is JsonArray -> JsonRootKind.Array
                is JsonPrimitive -> JsonRootKind.Primitive
            }
            ToolOutputBlock.Json(
                raw = text,
                pretty = json.encodeToString(
                    JsonElement.serializer(),
                    element,
                ),
                rootKind = rootKind,
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalizeJsonResultFields(
        element: JsonElement,
        insideResultField: Boolean = false,
    ): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                normalizeJsonResultFields(
                    element = value,
                    insideResultField = insideResultField || key.isResultFieldName(),
                )
            }
        )
        is JsonArray -> JsonArray(
            element.map { value ->
                normalizeJsonResultFields(value, insideResultField)
            }
        )
        is JsonPrimitive -> if (insideResultField && element.isString) {
            JsonPrimitive(element.content.stripResultBlankWhitespace())
        } else {
            element
        }
    }

    private fun String.isResultFieldName(): Boolean =
        equals("result", ignoreCase = true) || equals("results", ignoreCase = true)

    private fun String.stripResultBlankWhitespace(): String {
        val lines = normalizeLineEndings()
            .lineSequence()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .toList()
            .dropLastWhile { it.isBlank() }
        return lines.filterNot { it.isBlank() }.joinToString("\n")
    }

    private fun parseDiff(text: String): ToolOutputBlock.Diff? {
        val lines = text.lines()
        val hasDiffMarker = lines.any {
            it.startsWith("diff --git ") || it.startsWith("@@ ") || it.startsWith("--- ") || it.startsWith("+++ ")
        }
        if (!hasDiffMarker || lines.none { it.startsWith("@@ ") }) return null

        val files = mutableListOf<DiffFile>()
        var oldPath: String? = null
        var newPath: String? = null
        var currentLines = mutableListOf<DiffLine>()

        fun flush() {
            if (oldPath != null || newPath != null || currentLines.isNotEmpty()) {
                files += DiffFile(oldPath = oldPath, newPath = newPath, lines = currentLines.toList())
            }
            oldPath = null
            newPath = null
            currentLines = mutableListOf()
        }

        lines.forEach { line ->
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    currentLines += DiffLine(DiffLineType.Header, line)
                    val parts = line.split(' ')
                    oldPath = parts.getOrNull(2)?.removePrefix("a/")
                    newPath = parts.getOrNull(3)?.removePrefix("b/")
                }
                line.startsWith("--- ") -> {
                    oldPath = line.removePrefix("--- ").trim().removePrefix("a/")
                    currentLines += DiffLine(DiffLineType.Header, line)
                }
                line.startsWith("+++ ") -> {
                    newPath = line.removePrefix("+++ ").trim().removePrefix("b/")
                    currentLines += DiffLine(DiffLineType.Header, line)
                }
                line.startsWith("@@ ") -> currentLines += DiffLine(DiffLineType.Hunk, line)
                line.startsWith("+") -> currentLines += DiffLine(DiffLineType.Added, line)
                line.startsWith("-") -> currentLines += DiffLine(DiffLineType.Removed, line)
                else -> currentLines += DiffLine(DiffLineType.Context, line)
            }
        }
        flush()

        return ToolOutputBlock.Diff(raw = text, files = files.filter { it.lines.isNotEmpty() })
    }

    private fun parseStackTrace(text: String): ToolOutputBlock.StackTrace? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        if (lines.first().startsWith("Traceback (most recent call last):")) {
            val frames = lines.mapNotNull { line ->
                pythonFrameRegex.find(line)?.let { match ->
                    StackFrame(
                        text = line.trim(),
                        file = match.groupValues[1],
                        line = match.groupValues[2].toIntOrNull(),
                        symbol = match.groupValues[3],
                    )
                }
            }
            if (frames.isNotEmpty()) {
                return ToolOutputBlock.StackTrace(
                    raw = text,
                    languageHint = "python",
                    headline = lines.last(),
                    frames = frames,
                )
            }
        }

        val jvmFrames = lines.mapNotNull { line ->
            jvmFrameRegex.find(line)?.let { match ->
                StackFrame(
                    text = line.trim(),
                    file = match.groupValues[2],
                    line = match.groupValues[3].toIntOrNull(),
                    symbol = match.groupValues[1],
                )
            }
        }
        if (jvmFrames.isNotEmpty() && lines.any { it.contains("Exception") || it.contains("Error") }) {
            return ToolOutputBlock.StackTrace(
                raw = text,
                languageHint = "jvm",
                headline = lines.first(),
                frames = jvmFrames,
            )
        }

        val jsFrames = lines.mapNotNull { line ->
            jsFrameRegex.find(line)?.let { match ->
                StackFrame(
                    text = line.trim(),
                    file = match.groupValues[2],
                    line = match.groupValues[3].toIntOrNull(),
                    symbol = match.groupValues[1],
                )
            }
        }
        if (jsFrames.isNotEmpty()) {
            return ToolOutputBlock.StackTrace(
                raw = text,
                languageHint = "javascript",
                headline = lines.first(),
                frames = jsFrames,
            )
        }

        return null
    }

    private fun parseShellTranscript(text: String): ToolOutputBlock.ShellTranscript? {
        val commandLines = mutableListOf<String>()
        val outputLines = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("$ ") || trimmed.startsWith("> ") || trimmed.startsWith("PS> ")) {
                commandLines += line
            } else {
                outputLines += line
            }
        }
        if (commandLines.isEmpty()) return null
        val output = outputLines.joinToString("\n").trim()
        return ToolOutputBlock.ShellTranscript(raw = text, commandLines = commandLines, output = output)
    }

    private fun parseTable(text: String): ToolOutputBlock.Table? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2 || lines.count { it.contains('|') } < 2) return null

        val rows = lines.mapNotNull(::parseTableRow)
        if (rows.size < 2) return null
        val separatorIndex = rows.indexOfFirst { row ->
            row.size >= 2 && row.all { tableSeparatorCellRegex.matches(it.trim()) }
        }
        if (separatorIndex <= 0) return null
        val width = rows[separatorIndex].size
        if (rows.any { it.size != width }) return null

        return ToolOutputBlock.Table(
            raw = text,
            rows = rows.filterIndexed { index, _ -> index != separatorIndex },
        )
    }

    private fun parseTableRow(line: String): List<String>? {
        if (!line.contains('|')) return null
        val trimmed = line.trim()
        val body = trimmed.trim('|')
        val cells = body.split('|').map { it.trim() }
        return cells.takeIf { it.size >= 2 && it.any { cell -> cell.isNotBlank() } }
    }

    private fun parseCodeLike(text: String): ToolOutputBlock.CodeLike? {
        val trimmed = text.trimStart()
        val language = when {
            trimmed.startsWith("#!/bin/bash") || trimmed.startsWith("#!/usr/bin/env bash") -> "bash"
            trimmed.startsWith("fun ") || trimmed.contains("\nfun ") -> "kotlin"
            trimmed.startsWith("class ") && trimmed.contains("{") -> "kotlin"
            trimmed.startsWith("def ") || trimmed.contains("\ndef ") -> "python"
            trimmed.startsWith("import ") && trimmed.contains("\n") -> "python"
            trimmed.startsWith("function ") || trimmed.startsWith("const ") || trimmed.startsWith("let ") -> "javascript"
            trimmed.startsWith("<") && trimmed.contains(">") -> "xml"
            else -> null
        }
        return language?.let { ToolOutputBlock.CodeLike(raw = text, languageHint = it) }
    }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')
}
