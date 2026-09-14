package com.letta.mobile.ui.mascot

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
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

    /** The pointer in window coordinates, or null when it has left the window; every mascot looks toward it. */
    val cursor = mutableStateOf<Offset?>(null)

    fun update(all: Map<String, MascotIdentity>) {
        identities.keys.retainAll(all.keys)
        identities.putAll(all)
    }

    fun updatePresence(all: Map<String, AgentPresence>) {
        presence.keys.retainAll(all.keys)
        presence.putAll(all)
    }
}

/** The shell's [MascotIdentityRegistry]; the default is an empty one (no identities, so every avatar draws its fallback). */
val LocalMascotRegistry = compositionLocalOf { MascotIdentityRegistry() }
