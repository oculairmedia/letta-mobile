package com.letta.mobile.ui.chat.render

object ChatRenderStrings {
    fun charsOmitted(count: Int): String = "$count more characters not shown"

    fun diffFile(): String = "file"

    fun linesOmitted(count: Int): String = "$count more lines not shown"

    fun truncated(count: Int): String = "$count more characters not analyzed"
}
