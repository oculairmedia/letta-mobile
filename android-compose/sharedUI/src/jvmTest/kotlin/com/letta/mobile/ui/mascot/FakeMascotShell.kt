package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.testTag
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape

/** A renderer that can draw every agent and paints a tagged empty box: no scene, no frame loop. */
internal object FakeMascotHost : FakeMascotRenderer(observesPointer = false) {
    const val SURFACE_TAG = "fake-mascot-surface"
}

/**
 * [FakeMascotHost] whose surface also carries the pointer filter Android's Rive surface does
 * (RiveMascotSurface, RivePointerInputMode.PassThrough): it watches every pointer event, consumes
 * none, and shares input with its own siblings. Being hit at all is what matters to a hit test.
 */
internal object PointerObservingMascotHost : FakeMascotRenderer(observesPointer = true)

internal open class FakeMascotRenderer(private val observesPointer: Boolean) : MascotHost {
    override val available: Boolean = true

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry =
        object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
            override suspend fun load() = Unit
            override fun writeIdentity(identity: MascotIdentity) = Unit
            override fun dispose() = Unit
        }

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        val input = if (observesPointer) Modifier.then(RiveLikePointerFilter) else Modifier
        Box(modifier.testTag(FakeMascotHost.SURFACE_TAG).then(input))
    }
}

/** Rive's pass-through pointer filter: sees every event, consumes none, shares with its siblings. */
private data object RiveLikePointerFilter : ModifierNodeElement<RiveLikePointerNode>() {
    override fun create(): RiveLikePointerNode = RiveLikePointerNode()

    override fun update(node: RiveLikePointerNode) = Unit
}

private class RiveLikePointerNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
}

/**
 * The window's mascot shell for a test: [FakeMascotHost], a registry that knows [agentIds], and
 * [transport]. With [layerMounted] the transport behaves as under a [MascotTransportLayer] (seats
 * only reserve space; the layer would draw the character) without drawing a live scene.
 */
internal class FakeMascotShell(
    vararg agentIds: String,
    layerMounted: Boolean = true,
    /** The renderer: [PointerObservingMascotHost] to stand in for Android's Rive surface. */
    private val host: MascotHost = FakeMascotHost,
) {
    val transport = MascotTransport().also { it.layerMounted = layerMounted }
    val registry = MascotIdentityRegistry().also { registry ->
        registry.update(agentIds.associateWith { MascotIdentity(MascotShape.entries.first(), TEST_COLOR) })
    }

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalMascotHost provides host,
            LocalMascotRegistry provides registry,
            LocalMascotTransport provides transport,
            content = content,
        )
    }

    private companion object {
        const val TEST_COLOR = 0xFF00AA88.toInt()
    }
}
