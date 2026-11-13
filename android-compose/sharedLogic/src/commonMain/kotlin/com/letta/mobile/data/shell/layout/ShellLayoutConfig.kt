package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.chat.layout.ChatViews
import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
import kotlinx.serialization.Serializable

/**
 * One module in the shell: which view draws, and the object it shows ([objectRef] is null for a view
 * that needs none). This is the shell's only composition unit: a container is
 * a view whose own document holds slots, so nesting needs no second concept.
 *
 * @property view the view that draws this module.
 * @property objectRef the object the view shows, or null for a view that needs none.
 */
@Serializable
data class ModuleSlot(val view: RuntimeViewId, val objectRef: String? = null)

/** The lanes the shell frame draws. The frame owns which lanes exist; a [ShellLayoutConfig] owns what is in them. */
object ShellLanes {
    /** Beside the navigation; several modules share its width in a wide window. */
    const val MAIN = "main"

    /** After the main lane, each module at its own width. */
    const val SIDE = "side"

    /**
     * The Home page's sections, top to bottom. The page draws its header, search results and
     * refresh around them, and decides per width where a section goes (see [HomeViews]).
     */
    const val HOME = "home"

    /**
     * The agent rail's parts, top to bottom (see [RailViews]). The rail decides its width, whether
     * it is expanded, and that its agents fill the height between the others.
     */
    const val RAIL = "rail"

    /**
     * The agent panel's parts, top to bottom (see [PanelViews]). The panel keeps its dialogs and the
     * library's filter, and keeps Settings at its bottom wherever the lane lists it.
     */
    const val PANEL = "panel"

    /** The chat page's content, top to bottom; the frame adapts the composer to its presentation. */
    const val CHAT = "chat"
}

/** The views the shell itself supplies; the shell binds them on every host, so they are always available. */
object ShellViews {
    /** The current shell page (home, a chat, a library page). */
    val PAGE = RuntimeViewId("shell.page")

    /** The host's side pane (the desktop's background tasks). */
    val SIDE_PANE = RuntimeViewId("shell.side-pane")

    /**
     * One canvas board, named by the slot's object (its canvas id), drawn at the size its lane gives
     * it. A board the host cannot open is shown as unavailable. Not part of the default layout.
     */
    val CANVAS = RuntimeViewId("shell.canvas")

    /** The existing notebook note of a canvas id, sharing its note session; not in the default layout. */
    val NOTE = RuntimeViewId("shell.note")

    /**
     * The notes the host shares with this device, and the one opened from them in the document
     * workspace. It names no object: the person picks the note. A host that cannot reach shared
     * notes shows it as unavailable. Not part of the default layout; a compact window draws it over
     * the page (from either lane), which stays mounted beneath.
     */
    val DOCUMENT = RuntimeViewId("shell.document")

    /**
     * The open conversation's background tasks (its subagent runs), each dismissable, drawn at the
     * size its lane gives it. It names no object: it follows whichever conversation is open. A host
     * that cannot list tasks shows it as unavailable. Not part of the default layout.
     */
    val TASKS = RuntimeViewId("shell.tasks")

    /**
     * The pending secret request card: an agent asks for a secret and the person answers it in the
     * secure form this card opens. The shell mounts it itself, in a reserved place above the main
     * lane and only while a request is pending, so no layout (the person's or an agent's) places,
     * moves, removes or covers it: it is in [HostOwned], and a config naming it is ignored there.
     */
    val SECRET_REQUEST = RuntimeViewId("shell.secret-request")

    /** Views only the shell mounts: a layout never holds them, and an edit naming one is refused. */
    val HostOwned: Set<RuntimeViewId> = setOf(SECRET_REQUEST)
}

/**
 * What is in each shell lane, in order. [Default] is the shell as it was before lanes were
 * configurable. Plain data: it grants nothing, and a view it names that no host supplies is drawn
 * as unavailable rather than failing the shell.
 *
 * @property version the defaults this config has already been given; see [upgraded].
 * @property lanes the modules of each lane by lane id ([ShellLanes]), in the order they are drawn.
 */
@Serializable
data class ShellLayoutConfig(
    val version: Int = 0,
    val lanes: Map<String, List<ModuleSlot>> = emptyMap(),
) {
    /** The lane's modules, each one once, without any view only the shell mounts ([ShellViews.HostOwned]). */
    fun lane(id: String): List<ModuleSlot> = lanes[id].orEmpty().distinct().filterNot { it.view in ShellViews.HostOwned }

    /** The main lane always shows something: the page is the shell's recovery path, so an emptied lane gets it back. */
    fun mainLane(): List<ModuleSlot> = lane(ShellLanes.MAIN).ifEmpty { Default.lane(ShellLanes.MAIN) }

    /**
     * The main lane cut to the [room] modules the window can show side by side (a phone has room
     * for one). The page is kept before any other module; the rest keep their order.
     */
    fun mainLane(room: Int): List<ModuleSlot> {
        val all = mainLane()
        val kept = all.sortedByDescending { it.view == ShellViews.PAGE }.take(room.coerceAtLeast(1))
        return all.filter { it in kept }
    }

    /**
     * Home's sections, each once. A config that never had a home lane (one built in code rather
     * than read through [decode], which upgrades it) shows Home's default sections; an emptied lane
     * stays empty.
     */
    fun homeLane(): List<ModuleSlot> = laneOrDefault(ShellLanes.HOME)

    /** The agent rail's parts, each once; like [homeLane], a config without the lane shows the default parts. */
    fun railLane(): List<ModuleSlot> = laneOrDefault(ShellLanes.RAIL)

    /** The agent panel's parts, each once; like [homeLane], a config without the lane shows the default parts. */
    fun panelLane(): List<ModuleSlot> = laneOrDefault(ShellLanes.PANEL)

    /** Chat's content, each once; malformed persisted data cannot remove the ability to read or send. */
    fun chatLane(): List<ModuleSlot> {
        val saved = laneOrDefault(ShellLanes.CHAT)
        val required = listOf(ChatViews.TRANSCRIPT, ChatViews.COMPOSER)
        return saved + required.filter { view -> saved.none { it.view == view } }.map(::ModuleSlot)
    }

    private fun laneOrDefault(id: String): List<ModuleSlot> = if (id in lanes) lane(id) else Default.lane(id)

    /** The side lane, without anything the main lane already shows: a module is drawn once. */
    fun sideLane(): List<ModuleSlot> = lane(ShellLanes.SIDE) - mainLane().toSet()

    /**
     * Brought up to [VERSION]: each default module added since this config's [version] is added to
     * its lane once, unless the lane already shows its view. After that the config belongs to its
     * owner, and anything removed from it stays removed, including defaults of an earlier version.
     */
    fun upgraded(): ShellLayoutConfig {
        if (version >= VERSION) return this
        val added = DefaultModules.filter { it.since > version }.groupBy({ it.lane }, { it.slot })
        val merged = added.mapValues { (id, defaults) ->
            val saved = lane(id)
            saved + defaults.filter { default -> saved.none { it.view == default.view } }
        }
        return ShellLayoutConfig(version = VERSION, lanes = lanes + merged)
    }

    /** This config as the text that is saved; [decode] reads it back. */
    fun encode(): String = LettaJson.persisted.encodeToString(serializer(), this)

    /** The default layout and reading a saved one. */
    companion object {
        /** The current set of defaults; a config below it gains the default modules it lacks. */
        const val VERSION = 4

        /** One default module: the [lane] it belongs to, its [slot], and the config version that added it. */
        private class DefaultModule(val lane: String, view: RuntimeViewId, val since: Int) {
            val slot = ModuleSlot(view)
        }

        /** Every default module with the [VERSION] that introduced it, in lane order. */
        private val DefaultModules: List<DefaultModule> = listOf(
            DefaultModule(ShellLanes.MAIN, ShellViews.PAGE, since = 1),
            DefaultModule(ShellLanes.SIDE, ShellViews.SIDE_PANE, since = 1),
        ) + HomeViews.Default.map { DefaultModule(ShellLanes.HOME, it, since = 2) } +
            RailViews.Default.map { DefaultModule(ShellLanes.RAIL, it, since = 3) } +
            PanelViews.Default.map { DefaultModule(ShellLanes.PANEL, it, since = 3) } +
            ChatViews.Default.map { DefaultModule(ShellLanes.CHAT, it, since = 4) }

        /**
         * The shell as it was before lanes were configurable: the page, then the host's side pane,
         * Home's sections in their wide order, and the rail's and the agent panel's parts top to bottom.
         */
        val Default = ShellLayoutConfig(
            version = VERSION,
            lanes = DefaultModules.groupBy({ it.lane }, { it.slot }),
        )

        /** The saved config, upgraded; [Default] when [text] is not a readable config. */
        fun decode(text: String): ShellLayoutConfig =
            runCatching { LettaJson.persisted.decodeFromString(serializer(), text).upgraded() }.getOrDefault(Default)
    }
}
