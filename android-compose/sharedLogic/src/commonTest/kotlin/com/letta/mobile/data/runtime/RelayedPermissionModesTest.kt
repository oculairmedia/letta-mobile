package com.letta.mobile.data.runtime

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.AcceptEdits
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Standard
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Unrestricted
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-bzvro.40: the permission-mode chip over an Iroh host's `permission_mode.*` relay. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayedPermissionModesTest {
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    /** A host that answers `permission_mode.*` from [hostMode]; records every call. */
    private class FakeHost(var hostMode: AppServerPermissionMode? = null, var confirm: Boolean = true, var fail: Boolean = false) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val rpc = AdminRpcInvoker { method, params ->
            calls += method to params
            if (fail) throw ModelControlException("host unreachable")
            answer(method, params)
        }

        private fun answer(method: String, params: JsonObject): JsonElement = when (method) {
            PermissionModeRpc.SET -> {
                val asked = PermissionModeRpc.modeOf(params)
                if (confirm) hostMode = asked
                PermissionModeRpc.result(hostMode, confirmed = confirm)
            }
            else -> PermissionModeRpc.result(hostMode)
        }
    }

    private fun modes(host: FakeHost, store: MapSettingsStore = MapSettingsStore(), capabilities: Set<String> = setOf(PermissionModeRpc.CAPABILITY)) =
        RelayedPermissionModes(PermissionModeSettings(store).modes, host.rpc) { capabilities }

    @Test
    fun `a confirmed change moves the chip`() = runTest {
        val host = FakeHost(hostMode = Unrestricted)
        val modes = modes(host)

        assertTrue(modes.set(runtime, Standard))

        assertEquals(Standard, modes.observe(runtime).first().mode)
        assertEquals(Standard, host.hostMode)
        assertEquals(PermissionModeRpc.SET, host.calls.single().first)
    }

    @Test
    fun `an unreachable host leaves the change unconfirmed and enforced as the stricter mode`() = runTest {
        val host = FakeHost(hostMode = Unrestricted)
        val modes = modes(host)
        modes.sync(runtime)
        host.fail = true

        assertFalse(modes.set(runtime, Standard))

        val state = modes.observe(runtime).first()
        assertEquals(Standard, state.unconfirmed)
        assertEquals(Standard, modes.modes.modeFor(runtime), "tightening holds while the outcome is unknown")
    }

    @Test
    fun `opening a conversation adopts the mode another client set on the host`() = runTest {
        val host = FakeHost(hostMode = AcceptEdits)
        val modes = modes(host)

        modes.sync(runtime)

        assertEquals(AcceptEdits, modes.observe(runtime).first().mode)
        assertEquals(listOf(PermissionModeRpc.GET), host.calls.map { it.first })
    }

    @Test
    fun `a choice persisted here is re-sent to a host that forgot it`() = runTest {
        val store = MapSettingsStore()
        val first = modes(FakeHost(hostMode = Unrestricted), store)
        first.set(runtime, Standard)

        // The host restarted (no mode) and the app restarted (a fresh registry over the same store).
        val restartedHost = FakeHost(hostMode = null)
        val restarted = modes(restartedHost, store)
        restarted.sync(runtime)

        assertEquals(Standard, restartedHost.hostMode)
        assertEquals(Standard, restarted.observe(runtime).first().mode)
    }

    @Test
    fun `an older host offers no chip and is never asked`() = runTest {
        val host = FakeHost(hostMode = Standard)
        val modes = modes(host, capabilities = emptySet())

        modes.sync(runtime)

        assertFalse(modes.supported)
        assertEquals(emptyList(), host.calls)
    }

    private fun push(mode: String, conversationId: String = "conv-1") =
        ServerFrame.PermissionModeUpdated(agentId = "agent-1", conversationId = conversationId, mode = mode)

    @Test
    fun `a host push moves the chip without asking the host again`() = runTest {
        val host = FakeHost(hostMode = Unrestricted)
        val modes = modes(host)
        modes.sync(runtime)
        val frames = MutableSharedFlow<ServerFrame>(replay = 8)
        modes.bind(backgroundScope, frames)

        frames.emit(push("acceptEdits"))
        frames.emit(push("strict", conversationId = "conv-other"))
        frames.emit(push("yolo"))
        runCurrent()

        assertEquals(AcceptEdits, modes.observe(runtime).first().mode, "another client's change is adopted; an unknown mode is ignored")
        assertEquals(AppServerPermissionMode.Strict, modes.observe(AppServerRuntimeScope("agent-1", "conv-other")).first().mode)
        assertEquals(listOf(PermissionModeRpc.GET), host.calls.map { it.first }, "a push is never echoed back to the host")
    }

    @Test
    fun `a push does not clobber a change this client still has in flight`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var hostMode: AppServerPermissionMode = Unrestricted
        val rpc = AdminRpcInvoker { method, params ->
            calls += method
            if (method == PermissionModeRpc.SET) {
                answer.await()
                hostMode = PermissionModeRpc.modeOf(params) ?: error("set without a mode")
                PermissionModeRpc.result(hostMode, confirmed = true)
            } else {
                PermissionModeRpc.result(hostMode)
            }
        }
        val modes = RelayedPermissionModes(PermissionModeSettings(MapSettingsStore()).modes, rpc) { setOf(PermissionModeRpc.CAPABILITY) }
        modes.sync(runtime)
        val frames = MutableSharedFlow<ServerFrame>(replay = 8)
        modes.bind(backgroundScope, frames)

        val setting = async { modes.set(runtime, AppServerPermissionMode.Strict) }
        runCurrent()
        // Another client loosened it on the host meanwhile.
        frames.emit(push("acceptEdits"))
        runCurrent()

        val during = modes.observe(runtime).first()
        assertEquals(AppServerPermissionMode.Strict, during.pending, "this client's change is still the one shown")
        assertEquals(AppServerPermissionMode.Strict, modes.modes.modeFor(runtime), "and the stricter mode is enforced meanwhile")

        answer.complete(Unit)
        assertTrue(setting.await())
        assertEquals(AppServerPermissionMode.Strict, modes.observe(runtime).first().mode)
        assertEquals(listOf(PermissionModeRpc.GET, PermissionModeRpc.SET), calls, "nothing was re-sent because of the push")
    }

    @Test
    fun `the rpc params name the runtime and the wire mode`() {
        val params = PermissionModeRpc.params("agent-1", "conv-1", AcceptEdits)

        assertEquals(AcceptEdits, PermissionModeRpc.modeOf(params))
        assertNull(PermissionModeRpc.modeOf(PermissionModeRpc.params("agent-1", "conv-1")))
        assertEquals("acceptEdits", PermissionModeRpc.wire(AcceptEdits))
    }
}
