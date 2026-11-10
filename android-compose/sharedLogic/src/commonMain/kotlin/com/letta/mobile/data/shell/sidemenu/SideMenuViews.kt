package com.letta.mobile.data.shell.sidemenu

import com.letta.mobile.data.model.view.RuntimeViewId

/**
 * The agent rail's parts, each a view the shell layout's rail lane can hold. The same lane drives
 * the wide window's rail and the phone drawer's rail.
 */
object RailViews {
    /** The Home button. */
    val HOME = RuntimeViewId("rail.home")

    /** The agents: orbs while collapsed (and in the drawer), the agent library while expanded. */
    val AGENTS = RuntimeViewId("rail.agents")

    /** The New button, which opens the agent picker. */
    val NEW = RuntimeViewId("rail.new")

    /** The rail's parts as they always were, top to bottom. */
    val Default: List<RuntimeViewId> = listOf(HOME, AGENTS, NEW)
}

/**
 * The agent panel's parts, each a view the shell layout's panel lane can hold. The same lane drives
 * the wide window's sidebar and the phone drawer's panel.
 */
object PanelViews {
    /** The focused agent's mascot and name, its model card, and the agent menu. */
    val HEADER = RuntimeViewId("panel.header")

    /** The per-agent section rows (Memory, Schedules, Channels, Skills). */
    val SECTIONS = RuntimeViewId("panel.sections")

    /** The New chat row. */
    val NEW_CHAT = RuntimeViewId("panel.newChat")

    /**
     * The conversations under their archive filter, then the canvases: one list with one scroll,
     * one keyboard walk and one filter, so it is one part.
     */
    val LIBRARY = RuntimeViewId("panel.library")

    /** The Settings row, kept at the panel's bottom. */
    val SETTINGS = RuntimeViewId("panel.settings")

    /** The panel's parts as they always were, top to bottom. */
    val Default: List<RuntimeViewId> = listOf(HEADER, SECTIONS, NEW_CHAT, LIBRARY, SETTINGS)
}
