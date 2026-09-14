package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.letta.mobile.avatar.core.GazeRect
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentPresence

/**
 * Every agent's mascot identity and presence, as the app currently knows them. The shell owns
 * one instance and keeps it current (identity from the agent list, presence from
 * [com.letta.mobile.data.presence.AgentPresenceResolver]); any surface that draws an agent reads
 * it through [LocalMascotRegistry] and [MascotAvatar] without the identity having to be threaded
 * through every row model on the way. Platform-neutral: the same registry feeds the Android shell.
 */
class MascotIdentityRegistry {
    val identities = mutableStateMapOf<String, MascotIdentity>()

    /** Each agent's presence (activity, typing, approval, error) as the app derives it; absent means idle. */
    val presence = mutableStateMapOf<String, AgentPresence>()

    /** The pointer in window coordinates, or null when it has left the window. */
    val cursor = mutableStateOf<Offset?>(null)

    /** Window-space composer field; null when the chat composer is not composed. */
    val inputBounds = mutableStateOf<GazeRect?>(null)

    /** Window-space message timeline; null when the chat list is not composed. */
    val timelineBounds = mutableStateOf<GazeRect?>(null)

    fun update(all: Map<String, MascotIdentity>) {
        identities.keys.retainAll(all.keys)
        identities.putAll(all)
    }

    /**
     * Every live mascot surface's window bounds, keyed by surface (one agent can be drawn in
     * several places), so mascots can look at each other. [MascotLive] publishes and retracts.
     */
    val mascotBounds = mutableStateMapOf<String, MascotSlot>()

    /** Replaces every agent's presence at once - the shell that knows all agents (desktop) publishes this way. */
    fun updatePresence(all: Map<String, AgentPresence>) {
        presence.keys.retainAll(all.keys)
        presence.putAll(all)
    }

    /** Sets one agent's presence, leaving the others alone - a screen that knows one agent publishes this way. */
    fun setPresence(agentId: String, value: AgentPresence) {
        if (presence[agentId] != value) presence[agentId] = value
    }

    /** Forgets one agent's presence (it reads as idle); the others stay as they were. */
    fun clearPresence(agentId: String) {
        presence.remove(agentId)
    }
}

/** Which host surface [Modifier.mascotGazeTarget] publishes into the registry. */
enum class MascotGazeSurface {
    INPUT,
    TIMELINE,
}

/**
 * Publishes this node's window bounds as a [GazeDirector] target. Null when
 * the node leaves composition so OWN / USER / CURSOR still run without a
 * dead look at the origin. Android chat can attach the same modifier later
 * (letta-mobile-jwntc).
 */
@Composable
fun Modifier.mascotGazeTarget(surface: MascotGazeSurface): Modifier {
    val registry = LocalMascotRegistry.current
    val slot = when (surface) {
        MascotGazeSurface.INPUT -> registry.inputBounds
        MascotGazeSurface.TIMELINE -> registry.timelineBounds
    }
    DisposableEffect(slot) {
        onDispose { slot.value = null }
    }
    return this.onGloballyPositioned { coords ->
        val r = coords.boundsInWindow()
        val next = GazeRect(r.left, r.top, r.right, r.bottom)
        if (slot.value != next) slot.value = next
    }
}

/** The shell's [MascotIdentityRegistry]; the default is an empty one (no identities, so every avatar draws its fallback). */
val LocalMascotRegistry = compositionLocalOf { MascotIdentityRegistry() }

/** One drawn mascot: which agent, and where it is in the window. */
data class MascotSlot(val agentId: String, val bounds: GazeRect)
