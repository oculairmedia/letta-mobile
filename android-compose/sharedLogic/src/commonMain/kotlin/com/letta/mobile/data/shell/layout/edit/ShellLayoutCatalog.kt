package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews

/**
 * One view a lane may hold, as an agent is told of it.
 *
 * @property view the view id a slot names.
 * @property label the view's short name, as the person reads it in a notice.
 * @property description one line on what the view shows.
 * @property needsObject true when a slot of this view must name its object (a canvas board's id).
 */
data class ShellLayoutViewSpec(
    val view: RuntimeViewId,
    val label: String,
    val description: String,
    val needsObject: Boolean = false,
)

/**
 * One lane an agent may change: what it is, the views it may hold and how many slots it takes.
 *
 * @property lane the lane id ([ShellLanes]).
 * @property label the lane's short name, as the person reads it in a notice.
 * @property description one line on where the lane is drawn.
 * @property views the views the lane may hold.
 * @property maxSlots the most slots the lane may hold.
 */
data class ShellLayoutLaneSpec(
    val lane: String,
    val label: String,
    val description: String,
    val views: List<ShellLayoutViewSpec>,
    val maxSlots: Int,
) {
    /** The spec of [view] in this lane, or null when the lane may not hold it. */
    fun view(view: RuntimeViewId): ShellLayoutViewSpec? = views.firstOrNull { it.view == view }
}

/**
 * What an agent may place where in the shell: every lane it may change, with the views each may
 * hold, described in a line each so the agent can discover them. The edit rules of
 * [ShellLayoutEdits] are checked against this catalog.
 */
object ShellLayoutCatalog {
    /** The longest object reference a slot may carry. */
    const val MAX_OBJECT_LENGTH = 200

    private val page = ShellLayoutViewSpec(
        ShellViews.PAGE,
        "Page",
        "The current page (Home, a chat, a library page). Always in the main lane; it cannot be removed.",
    )
    private val canvas = ShellLayoutViewSpec(
        ShellViews.CANVAS,
        "Canvas",
        "One canvas board; object is its canvas id. Shown only in a wide window.",
        needsObject = true,
    )
    private val sidePane =
        ShellLayoutViewSpec(ShellViews.SIDE_PANE, "Side pane", "The host's own side pane (the desktop's background tasks).")

    private val homeViews = listOf(
        ShellLayoutViewSpec(HomeViews.COMPOSER, "Composer", "The quick-chat composer."),
        ShellLayoutViewSpec(HomeViews.NOW, "Running now", "A strip of the agents running now (only while something runs)."),
        ShellLayoutViewSpec(HomeViews.RECENTS, "Recents", "The recent conversations, grouped by day."),
        ShellLayoutViewSpec(HomeViews.AGENTS, "Agents", "The agents as a row of avatars, pinned first."),
        ShellLayoutViewSpec(HomeViews.STATS, "Stats", "A strip of backend counters."),
        ShellLayoutViewSpec(HomeViews.PINNED, "Pinned", "The pinned shortcuts."),
        ShellLayoutViewSpec(HomeViews.FLEET, "Fleet", "The sortable table of every agent (wide windows only)."),
    )

    private val railViews = listOf(
        ShellLayoutViewSpec(RailViews.HOME, "Home button", "The button that opens Home."),
        ShellLayoutViewSpec(RailViews.AGENTS, "Agents", "The agents: orbs while collapsed, the agent library while expanded."),
        ShellLayoutViewSpec(RailViews.NEW, "New button", "The button that opens the new-agent picker."),
    )

    private val panelViews = listOf(
        ShellLayoutViewSpec(PanelViews.HEADER, "Agent header", "The focused agent's mascot, name, model card and menu."),
        ShellLayoutViewSpec(PanelViews.SECTIONS, "Sections", "The agent's section rows (Memory, Schedules, Channels, Skills)."),
        ShellLayoutViewSpec(PanelViews.NEW_CHAT, "New chat", "The New chat row."),
        ShellLayoutViewSpec(PanelViews.LIBRARY, "Chats", "The agent's conversations and canvases."),
        ShellLayoutViewSpec(PanelViews.SETTINGS, "Settings", "The Settings row, kept at the bottom. It cannot be removed."),
    )

    /** Every lane an agent may change, in the order the catalog describes them. */
    val lanes: List<ShellLayoutLaneSpec> = listOf(
        ShellLayoutLaneSpec(
            ShellLanes.MAIN,
            "Main area",
            "Beside the navigation; its modules share the width in a wide window, a phone shows the page alone.",
            listOf(page, canvas),
            maxSlots = 4,
        ),
        ShellLayoutLaneSpec(
            ShellLanes.SIDE,
            "Side area",
            "After the main area, each module at its own width; a canvas here shows only in a wide window.",
            listOf(sidePane, canvas),
            maxSlots = 3,
        ),
        ShellLayoutLaneSpec(ShellLanes.HOME, "Home", "The Home page's sections, top to bottom.", homeViews, homeViews.size),
        ShellLayoutLaneSpec(ShellLanes.RAIL, "Agent rail", "The narrow agent rail's parts, top to bottom.", railViews, railViews.size),
        ShellLayoutLaneSpec(ShellLanes.PANEL, "Side menu", "The agent panel's parts, top to bottom.", panelViews, panelViews.size),
    )

    /** The lane [id], or null when an agent may not change it (or it does not exist). */
    fun lane(id: String): ShellLayoutLaneSpec? = lanes.firstOrNull { it.lane == id }

    /** The person-facing name of [slot] in [lane]: its view's label, or the view id when the catalog has none. */
    fun label(lane: String, slot: ModuleSlot): String = lane(lane)?.view(slot.view)?.label ?: slot.view.value
}
