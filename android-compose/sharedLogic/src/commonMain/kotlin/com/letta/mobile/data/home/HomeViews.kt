package com.letta.mobile.data.home

import com.letta.mobile.data.model.view.RuntimeViewId

/**
 * The Home page's sections, each a view the shell layout's home lane can hold. The lane's order is
 * the order on a wide page; a compact page keeps the composer docked below the list wherever the
 * lane puts it, and leaves the fleet table out.
 */
object HomeViews {
    /** The quick-chat composer. */
    val COMPOSER = RuntimeViewId("home.composer")

    /** The one-line strip of agents running now (only while something runs). */
    val NOW = RuntimeViewId("home.now")

    /** The recent conversations, grouped by day. */
    val RECENTS = RuntimeViewId("home.recents")

    /** The agents as a row of avatars, pinned first. */
    val AGENTS = RuntimeViewId("home.agents")

    /** The one-line strip of backend counters. */
    val STATS = RuntimeViewId("home.stats")

    /** The pinned shortcuts, or the pinned-tile editor while it is open. */
    val PINNED = RuntimeViewId("home.pinned")

    /** The sortable table of every agent (wide pages only). */
    val FLEET = RuntimeViewId("home.fleet")

    /** Every section in the order of a wide page: the home lane of a layout nobody has changed. */
    val Default: List<RuntimeViewId> = listOf(COMPOSER, NOW, RECENTS, AGENTS, STATS, PINNED, FLEET)
}
