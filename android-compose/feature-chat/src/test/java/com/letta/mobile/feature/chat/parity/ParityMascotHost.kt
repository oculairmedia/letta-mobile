package com.letta.mobile.feature.chat.parity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.MascotTransport

/**
 * A mascot renderer for the parity harness: every agent has a mascot, drawn as a flat disc in the
 * agent's colour (no Rive scene, no frame loop), so the seat a row reserves for the character shows
 * in the PNG the same way on both sides. No transport layer is mounted: each seat draws its own
 * character, as on Android.
 */
private object DiscMascotHost : MascotHost {
    override val available: Boolean = true

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry =
        object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
            override suspend fun load() = Unit
            override fun writeIdentity(identity: MascotIdentity) = Unit
            override fun dispose() = Unit
        }

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        Box(modifier.clip(CircleShape).background(Color(entry.identity.argb)))
    }
}

/** Provides [DiscMascotHost] with [agentId] known and thinking, around [content]. */
@Composable
internal fun WithParityMascot(agentId: String, content: @Composable () -> Unit) {
    val registry = remember(agentId) {
        MascotIdentityRegistry().apply {
            update(mapOf(agentId to MascotIdentity(MascotShape.entries.first(), PARITY_MASCOT_COLOR)))
            setPresence(agentId, AgentPresence(activity = AgentActivityKind.THINKING))
        }
    }
    val transport = remember { MascotTransport() }
    CompositionLocalProvider(
        LocalMascotHost provides DiscMascotHost,
        LocalMascotRegistry provides registry,
        LocalMascotTransport provides transport,
        content = content,
    )
}

private const val PARITY_MASCOT_COLOR = 0xFF00AA88.toInt()
