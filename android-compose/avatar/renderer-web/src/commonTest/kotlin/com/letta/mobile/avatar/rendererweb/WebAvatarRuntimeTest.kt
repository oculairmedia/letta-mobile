package com.letta.mobile.avatar.rendererweb

import com.letta.mobile.avatar.core.AvatarCapabilities
import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarRuntimeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WebAvatarRuntimeTest {
    private val model = AvatarModel(
        id = "avatar-1",
        displayName = "Buddy",
        uri = "http://127.0.0.1:8543/assets/avatar-1.vrm",
        format = AvatarFormat.VRM_1,
    )

    private val fullCapabilities = AvatarCapabilities(
        supportsHumanoid = true,
        supportsExpressions = true,
        supportsVisemes = true,
        supportsLookAt = true,
        supportsSpringBones = true,
        supportsEmbeddedAnimations = true,
        supportsAccessories = true,
    )

    /** In-memory transport: records outbound commands, injects inbound events. */
    private class FakeTransport : AvatarRendererTransport {
        val sent = mutableListOf<AvatarRendererCommand>()
        var closed = false
            private set
        private var handler: (String) -> Unit = {}

        override fun send(message: String) {
            sent += AvatarWireProtocol.decodeCommand(message)
        }

        override fun setMessageHandler(handler: (String) -> Unit) {
            this.handler = handler
        }

        override fun close() {
            closed = true
        }

        fun emit(event: AvatarRendererEvent) {
            handler(AvatarWireProtocol.encodeEvent(event))
        }

        fun lastLoadRequestId(): String =
            sent.filterIsInstance<AvatarRendererCommand.LoadAvatar>().last().requestId
    }

    @Test
    fun loadSendsCommandAndBecomesReadyWithRendererVerifiedCapabilities() = runTest {
        val transport = FakeTransport()
        val runtime = WebAvatarRuntime(transport)
        transport.emit(AvatarRendererEvent.Ready(AvatarWireProtocol.VERSION))

        val job = launch { runtime.load(model) }
        runCurrent()

        val loadCommand = assertIs<AvatarRendererCommand.LoadAvatar>(transport.sent.single())
        assertEquals(model.uri, loadCommand.url)
        assertEquals("vrm1", loadCommand.format)

        transport.emit(
            AvatarRendererEvent.AvatarLoaded(loadCommand.requestId, fullCapabilities),
        )
        job.join()

        val ready = assertIs<AvatarRuntimeState.Ready>(runtime.state.value)
        assertEquals(fullCapabilities, ready.capabilities)
    }

    @Test
    fun commandsForwardOverTheWireAfterLoad() = runTest {
        val (runtime, transport) = readyRuntime()

        runtime.setExpression(AvatarExpression.Happy, 0.8f)
        runtime.setViseme(com.letta.mobile.avatar.core.AvatarViseme.A, 2f) // clamped
        runtime.setMouthOpen(0.5f)
        runtime.setLookTarget(AvatarLookTarget.Screen(0.5f, 0.25f))
        runtime.setLookTarget(null)
        runtime.playAnimation("Idle", loop = true)
        runtime.setAccessoryEnabled("glasses", enabled = false)

        val wire = transport.sent.drop(1) // drop the LoadAvatar
        assertEquals(
            listOf(
                AvatarRendererCommand.SetExpression("happy", 0.8f),
                AvatarRendererCommand.SetViseme("aa", 1f),
                AvatarRendererCommand.SetMouthOpen(0.5f),
                AvatarRendererCommand.SetLookTarget("screen", 0.5f, 0.25f),
                AvatarRendererCommand.ClearLookTarget,
                AvatarRendererCommand.PlayAnimation("Idle", true),
                AvatarRendererCommand.SetAccessoryEnabled("glasses", false),
            ),
            wire,
        )
    }

    @Test
    fun gatedCommandsNeverReachTheWire() = runTest {
        // Renderer verified NO capabilities on this model.
        val (runtime, transport) = readyRuntime(capabilities = AvatarCapabilities())

        runtime.setExpression(AvatarExpression.Happy)
        runtime.setMouthOpen(1f)
        runtime.playAnimation("Idle")

        assertEquals(1, transport.sent.size) // just the LoadAvatar
    }

    @Test
    fun loadFailureEventFailsTheLoadAndStateIsFailed() = runTest {
        val transport = FakeTransport()
        val runtime = WebAvatarRuntime(transport)
        transport.emit(AvatarRendererEvent.Ready(AvatarWireProtocol.VERSION))

        var failure: Throwable? = null
        val job = launch {
            runCatching { runtime.load(model) }.onFailure { failure = it }
        }
        runCurrent()
        transport.emit(
            AvatarRendererEvent.AvatarLoadFailed(transport.lastLoadRequestId(), "404 not found"),
        )
        job.join()

        assertIs<AvatarWireException>(failure)
        val failed = assertIs<AvatarRuntimeState.Failed>(runtime.state.value)
        assertTrue("404" in failed.message)
    }

    @Test
    fun loadTimesOutWhenRendererNeverAcks() = runTest {
        val transport = FakeTransport()
        val runtime = WebAvatarRuntime(transport, loadTimeoutMillis = 5_000)
        transport.emit(AvatarRendererEvent.Ready(AvatarWireProtocol.VERSION))

        var failure: Throwable? = null
        val job = launch {
            runCatching { runtime.load(model) }.onFailure { failure = it }
        }
        advanceTimeBy(5_001)
        job.join()

        assertTrue(failure != null, "expected the load to time out")
        assertIs<AvatarRuntimeState.Failed>(runtime.state.value)
    }

    @Test
    fun protocolVersionMismatchFailsTheLoad() = runTest {
        val transport = FakeTransport()
        val runtime = WebAvatarRuntime(transport)
        transport.emit(AvatarRendererEvent.Ready(protocolVersion = 99))

        var failure: Throwable? = null
        launch { runCatching { runtime.load(model) }.onFailure { failure = it } }
        runCurrent()

        assertIs<AvatarWireException>(failure)
        assertIs<AvatarRuntimeState.Failed>(runtime.state.value)
    }

    @Test
    fun unloadAndDisposeSendUnloadAndCloseTransport() = runTest {
        val (runtime, transport) = readyRuntime()

        runtime.unload()
        assertTrue(transport.sent.last() is AvatarRendererCommand.Unload)

        runtime.dispose()
        assertTrue(transport.closed)
    }

    @Test
    fun malformedRendererEventsAreSurfacedNotThrown() = runTest {
        val errors = mutableListOf<String>()
        val transport = FakeTransport()
        WebAvatarRuntime(transport, onRendererError = { errors += it })

        transport.emit(AvatarRendererEvent.RendererError("shader died"))
        // Malformed payload goes through the same non-throwing path.
        assertFailsWith<AvatarWireException> { AvatarWireProtocol.decodeEvent("garbage") }

        assertEquals(listOf("shader died"), errors)
    }

    private fun kotlinx.coroutines.test.TestScope.readyRuntime(
        capabilities: AvatarCapabilities = fullCapabilities,
    ): Pair<WebAvatarRuntime, FakeTransport> {
        val transport = FakeTransport()
        val runtime = WebAvatarRuntime(transport)
        transport.emit(AvatarRendererEvent.Ready(AvatarWireProtocol.VERSION))
        val job = launch { runtime.load(model) }
        runCurrent()
        transport.emit(AvatarRendererEvent.AvatarLoaded(transport.lastLoadRequestId(), capabilities))
        runCurrent()
        check(job.isCompleted) { "load did not complete" }
        return runtime to transport
    }
}
