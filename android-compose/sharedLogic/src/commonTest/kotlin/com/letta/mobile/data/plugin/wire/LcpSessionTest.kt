package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.ElementEventType
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHealth
import com.letta.mobile.plugin.api.PluginInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The handshake and the session state machine (letta-mobile-s416w.25), both ends over the loopback. */
class LcpSessionTest {
    private val empty = JsonObject(emptyMap())

    @Test
    fun theHandshakeAgreesOnAContractVersionAndActivates() = runTest {
        val rig = LcpTestRig(backgroundScope)
        val result = rig.initialize(buildJsonObject { put("baseUrl", "https://api.example.test") })
        assertEquals(InitializeResult(true, 1, PluginInfo(build = "1.2.0")), result)
        assertEquals(LcpSessionState.INITIALIZED, rig.hostSession.state.value)
        rig.hostSession.activate()
        assertEquals(LcpSessionState.ACTIVE, rig.hostSession.state.value)
        assertEquals(LcpSessionState.ACTIVE, rig.pluginSession.state.value)
    }

    @Test
    fun aPluginSpeakingNoOfferedVersionRefusesAndTheHostCloses() = runTest {
        val rig = LcpTestRig(backgroundScope).apply { plugin.spoken = listOf(2) }
        val failure = assertFailsWith<LcpCallException> { rig.initialize() }
        assertEquals(LcpErrorCode.CONTRACT_MISMATCH, failure.code)
        assertEquals(LcpSessionState.CLOSED, rig.hostSession.state.value)
    }

    @Test
    fun aPluginAnsweringAVersionTheHostDidNotOfferIsRefused() = runTest {
        val rig = LcpTestRig(backgroundScope)
        rig.pluginSession.peer.handle(LcpMethod.INITIALIZE) {
            LcpCalls.INITIALIZE.encodeResult(InitializeResult(true, 2, PluginInfo(build = "9.0.0")))
        }
        assertEquals(LcpErrorCode.CONTRACT_MISMATCH, assertFailsWith<LcpCallException> { rig.initialize() }.code)
        assertEquals(LcpSessionState.CLOSED, rig.hostSession.state.value)
    }

    @Test
    fun theHandshakeChoosesTheBestVersionBothSpeak() {
        assertEquals(2, LcpHandshake.choose(offered = listOf(1, 2, 3), spoken = listOf(1, 2)))
        assertEquals(LcpErrorCode.CONTRACT_MISMATCH, assertFailsWith<LcpCallException> { LcpHandshake.choose(listOf(1), listOf(2)) }.code)
    }

    @Test
    fun callsBeforeInitializeAreRefusedOnBothSides() = runTest {
        val rig = LcpTestRig(backgroundScope)
        val hostRefusal = assertFailsWith<LcpCallException> { rig.hostSession.invoke(LcpTestRig.agentInvoke("start")) }
        assertEquals(LcpErrorCode.NOT_INITIALIZED, hostRefusal.code)
        val pluginRefusal = assertFailsWith<LcpCallException> { rig.pluginSession.emit(PluginEmit(remove = listOf("el-1"))) }
        assertEquals(LcpErrorCode.NOT_INITIALIZED, pluginRefusal.code)
    }

    @Test
    fun thePluginRefusesAnInvokeThatSkipsTheHandshake() = runTest {
        val ends = LoopbackLcpTransport.pair()
        val plugin = LcpPluginSession(ends.second, backgroundScope)
        TestPlugin().serveOn(plugin)
        plugin.start()
        val raw = LcpPeer(ends.first, LcpPeerConfig(LcpSide.HOST), backgroundScope).also { it.start() }
        val refusal = assertFailsWith<LcpCallException> { raw.call(LcpCalls.INVOKE, LcpTestRig.agentInvoke("start")) }
        assertEquals(LcpErrorCode.NOT_INITIALIZED, refusal.code)
    }

    @Test
    fun aSecondInitializeIsRefused() = runTest {
        val rig = LcpTestRig(backgroundScope).ready()
        val refusal = assertFailsWith<LcpCallException> { rig.hostSession.peer.request(LcpMethod.INITIALIZE, empty) }
        assertEquals(LcpErrorCode.SESSION_STATE, refusal.code)
    }

    @Test
    fun actionsAnswerResultsAndThePluginsOwnFailures() = runTest {
        val rig = LcpTestRig(backgroundScope).ready()
        assertEquals((ActionResult.Ok("ran start")), rig.hostSession.invoke(LcpTestRig.agentInvoke("start")))
        rig.plugin.invoke = { throw LcpPluginSession.actionFailed("busy", "a job is already running") }
        assertEquals(ActionResult.Error("busy", "a job is already running"), rig.hostSession.invoke(LcpTestRig.agentInvoke("start")))
    }

    @Test
    fun eventsAndSettingsReachThePlugin() = runTest {
        val rig = LcpTestRig(backgroundScope).ready()
        rig.hostSession.elementEvent(ElementEvent("widget", "el-1", ElementEventType.REMOVED))
        rig.hostSession.settingsChanged(SettingsChangedParams(buildJsonObject { put("quality", 90) }))
        assertEquals(PluginHealth.Ok, rig.hostSession.health())
        assertEquals(listOf(ElementEvent("widget", "el-1", ElementEventType.REMOVED)), rig.plugin.events)
        assertEquals(listOf(buildJsonObject { put("quality", 90) }), rig.plugin.settings)
    }

    @Test
    fun deactivateDrainsTheActionsInFlightThenCloses() = runTest {
        val rig = LcpTestRig(backgroundScope).ready()
        val release = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        rig.plugin.invoke = {
            running.complete(Unit)
            release.await()
            ActionResult.Ok("finished", emit = null).also { rig.pluginSession.emit(PluginEmit(remove = listOf("el-9"))) }
        }
        val action = async { rig.hostSession.invoke(LcpTestRig.agentInvoke("start")) }
        running.await()
        val stopping = async { rig.hostSession.deactivate() }
        testScheduler.runCurrent()
        assertEquals(LcpSessionState.STOPPING, rig.hostSession.state.value)
        val refused = assertFailsWith<LcpCallException> { rig.hostSession.invoke(LcpTestRig.agentInvoke("start")) }
        assertEquals(LcpErrorCode.SESSION_STATE, refused.code)
        assertTrue(!rig.plugin.deactivated, "deactivate waits for the action")
        release.complete(Unit)
        assertEquals((ActionResult.Ok("finished")), action.await())
        stopping.await()
        assertTrue(rig.plugin.deactivated)
        assertEquals(listOf(PluginEmit(remove = listOf("el-9"))), rig.host.emits, "the drained action's emit was served")
        assertEquals(LcpSessionState.CLOSED, rig.hostSession.state.value)
    }

    @Test
    fun theStateMachineAdmitsEachStatesMethods() {
        val session = PluginWireSession()
        assertIs<LcpAdmission.Refuse>(session.incoming(LcpMethod.HEALTH, empty))
        assertIs<LcpAdmission.Admit>(session.outgoing(LcpMethod.INITIALIZE, empty))
        session.completed(LcpMethod.INITIALIZE, ok = true)
        assertIs<LcpAdmission.Refuse>(session.incoming(LcpMethod.INVOKE, empty))
        assertIs<LcpAdmission.Admit>(session.incoming(LcpMethod.EMIT, empty))
        session.completed(LcpMethod.ACTIVATE, ok = true)
        assertIs<LcpAdmission.Admit>(session.incoming(LcpMethod.INVOKE, empty))
        session.stop()
        session.completed(LcpMethod.ACTIVATE, ok = true)
        assertEquals(LcpSessionState.STOPPING, session.state.value, "a late answer never reopens the session")
        session.completed(LcpMethod.DEACTIVATE, ok = true)
        assertIs<LcpAdmission.Refuse>(session.incoming(LcpMethod.LOG, empty))
    }
}
