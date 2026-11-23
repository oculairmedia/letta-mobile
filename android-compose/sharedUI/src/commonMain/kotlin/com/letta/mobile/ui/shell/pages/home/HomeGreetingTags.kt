package com.letta.mobile.ui.shell.pages.home

/** Test tags for Home's greeting. */
object HomeGreetingTags {
    const val GREETING = "home_greeting"
    const val LINE = "home_greeting_line"
    const val STATUS = "home_greeting_status"
    const val MARK = "home_greeting_mark"
    const val STAGE = "home_greeting_stage"

    fun mascot(agentId: String): String = "home_greeting_mascot_$agentId"
}
