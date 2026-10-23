package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolPreviewTarget

/** The file a file tool names, across toolsets (`file_path`, Gemini's `absolute_path`, `path`). */
internal fun ToolCallFacts.filePath(): String? = FILE_PATH_KEYS.firstNotNullOfOrNull(::string)

/** [path] as a preview of the file, at [lines] when the call names some; nothing without a path. */
internal fun filePreview(path: String?, lines: IntRange? = null): List<ToolPreviewTarget> =
    listOfNotNull(path?.let { ToolPreviewTarget.File(it, lines) })

/** The directory a search runs in, when the call names one. */
internal fun ToolCallFacts.searchRoot(): String? = SEARCH_ROOT_KEYS.firstNotNullOfOrNull(::string)

/** "pattern in dir", or just the pattern when the search runs in the working directory. */
internal fun inRoot(pattern: String, root: String?): String = if (root.isNullOrBlank()) pattern else "$pattern in $root"

/** A call's result when it has one worth reading, as plain text. */
internal fun ToolCallFacts.resultText(): String? = result?.takeIf { it.isNotBlank() }

private val FILE_PATH_KEYS = listOf("file_path", "absolute_path", "filePath", "path")
private val SEARCH_ROOT_KEYS = listOf("path", "dir_path", "directory")

/** [text]'s line count, a trailing newline not counting as a line of its own. */
internal fun lineCount(text: String): Int = text.trimEnd('\n').count { it == '\n' } + 1

/** "1 line", "12 lines". */
internal fun linesLabel(count: Int): String = if (count == 1) "1 line" else "$count lines"

/** [text]'s first [count] lines, each led by its number, then "… N more lines" when there are more. */
internal fun numberedHead(text: String, count: Int): String {
    val lines = text.trimEnd('\n').lineSequence().take(count + 1).toList()
    val shown = lines.take(count)
    val width = shown.size.toString().length
    val numbered = shown.mapIndexed { index, line -> "${(index + 1).toString().padStart(width)}  $line" }
    val more = lineCount(text) - shown.size
    val tail = when {
        more <= 0 -> emptyList()
        more == 1 -> listOf("… 1 more line")
        else -> listOf("… $more more lines")
    }
    return (numbered + tail).joinToString("\n")
}

/** Between a step target's parts: "notes.md · 12 lines". */
internal const val SEPARATOR = " · "
