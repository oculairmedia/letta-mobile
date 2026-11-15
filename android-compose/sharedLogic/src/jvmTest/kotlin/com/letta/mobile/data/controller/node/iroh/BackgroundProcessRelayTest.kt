package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import java.lang.reflect.Proxy
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundProcessRelayTest {
    @Test
    fun snapshotsReachOnlyConversationViewersAndReplayToLateViewers() = runTest(UnconfinedTestDispatcher()) {
        val channel = ScriptedAppServerTransport { emptyList() }
        val client = DefaultAppServerClient(channel, parentScope = backgroundScope)
        val registry = ConnectionRegistry()
        val endpoint = IrohNodeEndpoint(scope = backgroundScope, authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
            connectionRegistry = registry)
        endpoint.attachBackgroundProcesses(client)
        val phone = Viewer("phone")
        val desktop = Viewer("desktop")
        val elsewhere = Viewer("other")
        registry.register("c", registry.claim(phone))
        registry.register("other", registry.claim(elsewhere))
        runCurrent()
        channel.push("""{"type":"update_device_status","event_seq":1,"emitted_at":"2026-11-15T00:00:00Z","idempotency_key":"status",
            "runtime":{"agent_id":"a","conversation_id":"c"},
            "device_status":{"background_processes":[{"id":"p"}],"unknown_host_field":"preserved"}}""")
        runCurrent()
        assertEquals(1, phone.frames.size)
        runCurrent()
        assertTrue(elsewhere.frames.isEmpty())
        runCurrent()
        assertTrue(phone.frames.single().contains("preserved"))
        registry.register("c", registry.claim(desktop))
        runCurrent()
        assertEquals(phone.frames, desktop.frames)
        runCurrent()
        channel.push("""{"type":"update_device_status","event_seq":1,"emitted_at":"2026-11-15T00:00:00Z","idempotency_key":"status",
            "runtime":{"agent_id":"a","conversation_id":"c"},
            "device_status":{"background_processes":[]}}""")
        runCurrent()
        assertEquals(2, phone.frames.size)
        runCurrent()
        assertEquals(2, desktop.frames.size)
        val frame = AppServerProtocol.decodeFrame(desktop.frames.last()).frame as AppServerInboundFrame.UpdateDeviceStatus
        runCurrent()
        assertEquals("[]", frame.deviceStatus["background_processes"].toString())
    }

    @Test
    fun nativeConnectionReplacementClearsReplayAndRejectsOldSnapshots() = runTest(UnconfinedTestDispatcher()) {
        val frames = MutableSharedFlow<AppServerReceivedFrame>()
        val client = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) {
                _, method, _ ->
            check(method.name == "getEvents")
            frames
        } as AppServerClient
        val registry = ConnectionRegistry()
        IrohNodeEndpoint(scope = backgroundScope, authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
            connectionRegistry = registry).attachBackgroundProcesses(client)
        fun status(conversation: String, generation: Long) = AppServerProtocol.decodeFrame(
            """{"type":"update_device_status","event_seq":1,"emitted_at":"now","idempotency_key":"status",
                "runtime":{"agent_id":"a","conversation_id":"$conversation"},
                "device_status":{"background_processes":[{"id":"p"}]}}""",
        ).copy(connectionGeneration = generation)
        frames.emit(status("c", 1))
        runCurrent()
        frames.emit(status("other", 2))
        runCurrent()
        frames.emit(status("c", 1))
        runCurrent()
        val late = Viewer("late")
        registry.register("c", registry.claim(late))
        assertTrue(late.frames.isEmpty(), "the previous native connection cannot repopulate replay")
        frames.emit(status("c", 2))
        runCurrent()
        assertEquals(1, late.frames.size)
    }

    private class Viewer(override val connectionId: String) : ViewerHandle {
        val frames = mutableListOf<String>()
        override suspend fun writeFrame(frame: String): Boolean { frames += frame; return true }
    }
}
