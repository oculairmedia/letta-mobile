package com.letta.mobile.ui.devfixtures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.ui.mascot.MascotTransportLayer

/**
 * A mascot renderer for fixtures: every agent is a teal body with a white face, filling ~60 % of
 * its seat like the real characters, and no scene or frame loop behind it. The screenshots and the
 * playground use it where the native Rive bridge is absent (or must not be loaded, as in tests).
 */
object StandInMascotHost : MascotHost {
    override val available: Boolean = true

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry =
        object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
            override suspend fun load() = Unit
            override fun writeIdentity(identity: MascotIdentity) = Unit
            override fun dispose() = Unit
        }

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(BODY).background(BODY_COLOR, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(FACE_WIDTH).fillMaxHeight(FACE_HEIGHT).background(Color.White, CircleShape))
            }
        }
    }

    private const val BODY = 0.6f
    private const val FACE_WIDTH = 0.55f
    private const val FACE_HEIGHT = 0.3f
    private val BODY_COLOR = Color(0xFF00AA88)
}

/**
 * The window's mascot shell for a fixture: [host] (the stand-in unless a real renderer is handed
 * in), a registry that knows [PhoneFixtures.AGENT_ID], and a transport. With [transportLayer] the
 * characters draw on a [MascotTransportLayer] over [content], as on desktop; without it each seat
 * draws its own, as on Android.
 */
@Composable
fun FixtureMascotShell(
    host: MascotHost = StandInMascotHost,
    transportLayer: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The layer marks the transport mounted itself; without one, seats draw their own character.
    val transport = remember(transportLayer) { MascotTransport() }
    val registry = remember {
        MascotIdentityRegistry().also { registry ->
            registry.update(mapOf(PhoneFixtures.AGENT_ID to MascotIdentity(MascotShape.entries.first(), FIXTURE_MASCOT_COLOR)))
        }
    }
    CompositionLocalProvider(
        LocalMascotHost provides host,
        LocalMascotRegistry provides registry,
        LocalMascotTransport provides transport,
    ) {
        if (transportLayer) MascotTransportLayer { content() } else content()
    }
}

private const val FIXTURE_MASCOT_COLOR = 0xFF00AA88.toInt()
