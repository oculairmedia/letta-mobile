package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape

/** A renderer that can draw every agent and paints a tagged empty box: no scene, no frame loop. */
internal object FakeMascotHost : MascotHost {
    const val SURFACE_TAG = "fake-mascot-surface"

    override val available: Boolean = true

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry =
        object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
            override suspend fun load() = Unit
            override fun writeIdentity(identity: MascotIdentity) = Unit
            override fun dispose() = Unit
        }

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        Box(modifier.testTag(SURFACE_TAG))
    }
}

/**
 * The window's mascot shell for a test: [FakeMascotHost], a registry that knows [agentIds], and
 * [transport]. With [layerMounted] the transport behaves as under a [MascotTransportLayer] (seats
 * only reserve space; the layer would draw the character) without drawing a live scene.
 */
internal class FakeMascotShell(vararg agentIds: String, layerMounted: Boolean = true) {
    val transport = MascotTransport().also { it.layerMounted = layerMounted }
    val registry = MascotIdentityRegistry().also { registry ->
        registry.update(agentIds.associateWith { MascotIdentity(MascotShape.entries.first(), TEST_COLOR) })
    }

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalMascotHost provides FakeMascotHost,
            LocalMascotRegistry provides registry,
            LocalMascotTransport provides transport,
            content = content,
        )
    }

    private companion object {
        const val TEST_COLOR = 0xFF00AA88.toInt()
    }
}
