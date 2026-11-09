package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.Serializable

/**
 * One module in the shell: which view draws, and the object it shows ([objectRef] is null for a view
 * that needs none). This is the shell's only composition unit (letta-mobile-n2stx.2): a container is
 * a view whose own document holds slots, so nesting needs no second concept.
 */
@Serializable
data class ModuleSlot(val view: RuntimeViewId, val objectRef: String? = null)

/** The lanes the shell frame draws. The frame owns which lanes exist; a [ShellLayoutConfig] owns what is in them. */
object ShellLanes {
    /** Beside the navigation; several modules share its width in a wide window. */
    const val MAIN = "main"

    /** After the main lane, each module at its own width. */
    const val SIDE = "side"
}

/** The views the shell itself supplies; every host binds them, so they are always available. */
object ShellViews {
    /** The current shell page (home, a chat, a library page). */
    val PAGE = RuntimeViewId("shell.page")

    /** The host's side pane (the desktop's background tasks). */
    val SIDE_PANE = RuntimeViewId("shell.side-pane")
}

/**
 * What is in each shell lane, in order. [Default] is the shell as it was before lanes were
 * configurable. Plain data: it grants nothing, and a view it names that no host supplies is drawn
 * as unavailable rather than failing the shell.
 */
@Serializable
data class ShellLayoutConfig(
    val version: Int = 0,
    val lanes: Map<String, List<ModuleSlot>> = emptyMap(),
) {
    /** The lane's modules, each one once. */
    fun lane(id: String): List<ModuleSlot> = lanes[id].orEmpty().distinct()

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

    /** The side lane, without anything the main lane already shows: a module is drawn once. */
    fun sideLane(): List<ModuleSlot> = lane(ShellLanes.SIDE) - mainLane().toSet()

    /**
     * Brought up to [VERSION]: default modules a lane lacks are added once. After that the config
     * belongs to its owner, and anything removed from it stays removed.
     */
    fun upgraded(): ShellLayoutConfig {
        if (version >= VERSION) return this
        val merged = Default.lanes.mapValues { (id, defaults) ->
            val saved = lane(id)
            saved + defaults.filter { default -> saved.none { it.view == default.view } }
        }
        return ShellLayoutConfig(version = VERSION, lanes = lanes + merged)
    }

    fun encode(): String = LettaJson.persisted.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        val Default = ShellLayoutConfig(
            version = VERSION,
            lanes = mapOf(
                ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.PAGE)),
                ShellLanes.SIDE to listOf(ModuleSlot(ShellViews.SIDE_PANE)),
            ),
        )

        /** The saved config, upgraded; [Default] when [text] is not a readable config. */
        fun decode(text: String): ShellLayoutConfig =
            runCatching { LettaJson.persisted.decodeFromString(serializer(), text).upgraded() }.getOrDefault(Default)
    }
}
