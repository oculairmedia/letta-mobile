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
