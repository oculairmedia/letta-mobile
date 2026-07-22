package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.tooloutput.ToolOutputParser

/**
 * Maximum number of characters to render in a single tool output block.
 */
const val ToolOutputMaxRenderedChars = 20_000

/**
 * Maximum number of lines to render in a single tool output block.
 */
const val ToolOutputMaxRenderedLines = 320

/**
 * Maximum number of syntax highlight spans to apply to rendered tool output.
 */
const val ToolOutputMaxHighlightSpans = 800

/**
 * Maximum size (in raw characters) of a tool output document that will be cached.
 */
const val ToolOutputDocumentMaxCacheableRawChars = ToolOutputParser.MAX_ANALYZED_CHARS
