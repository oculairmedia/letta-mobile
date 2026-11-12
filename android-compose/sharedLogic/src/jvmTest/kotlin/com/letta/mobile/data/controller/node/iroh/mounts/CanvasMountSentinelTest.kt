package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.hoststate.DurableHostFileHooks
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Agent B's canvas: the one id that must never reach agent A's peer or any telemetry. */
private val MARKER = CanvasId("canvas-marker-agent-b-5e1f")
private val OWN = CanvasId("canvas-sentinel-own")

/**
 * Drives every outcome the routes can produce (found, none, bound, replaced, default_set, denied, invalid_input,
 * unavailable for storage, authority, identity and timeout, outcome_unknown) for agent A's peer while agent B's
 * marker canvas sits in records A's peer can address, with injected failures whose messages carry the temp root and
 * the marker. Then scans every response to A's peer and every telemetry event for the root path and the marker.
 */
class CanvasMountSentinelTest {
    @Test
    fun noResponseOrTelemetryEventCarriesTheRootPathOrAnotherAgentsCanvas() = runBlocking {
        val recorder = TelemetryRecorder()
        val root = Files.createTempDirectory("canvas-mount-sentinel")
        val responses = Telemetry.installRecorder(recorder).use { scenario(root) }
        val events = recorder.snapshot()
        assertTrue(events.any { it.name == "route.outcome" })
        assertNoHostDetail(root, responses + events.map { it.toLine() + it.attrs.toString() })
        (responses + events.map { it.toLine() + it.attrs.toString() }).forEach { assertTrue(MARKER.value !in it, it) }
        val outcomes = events.filter { it.name == "route.outcome" }.map { it.attrs.getValue("outcome").toString() }.toSet()
        assertEquals(EVERY_OUTCOME.size, EVERY_OUTCOME.count { outcome -> outcomes.any { outcome in it } }, "$outcomes")
    }

    private suspend fun scenario(root: Path): List<String> {
        val leak = "$root/canvas-mounts/${MARKER.value}"
        val harness = MountHarness(root, HarnessOptions(deadline = testDeadline(300.milliseconds, 300.milliseconds)))
        val store = harness.store()
        store.bindIfAbsent(AGENT_B, CONV_B, MARKER)
        store.bindIfAbsent(AGENT_A, CONV_A2, MARKER)
        harness.grant(OTHER_PEER, MARKER, CanvasMountGrant.MOUNT)
        harness.grant(PEER, OWN, CanvasMountGrant.MOUNT)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, OWN), PEER)
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, OWN), PEER)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, OWN), PEER)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A2), PEER)
        harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A2, OWN), PEER)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_B, CONV_B), PEER)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_B, CONV_B), PEER.copy(agents = setOf(AGENT_A.value)))
        harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_B, MARKER), PEER)
        harness.call(CanvasMountRoute.BIND, JsonObject(bindingParams(AGENT_A, CONV_A, OWN) + ("path" to JsonPrimitive(leak))), PEER)
        harness.call(CanvasMountWire.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER.copy(endpoint = "x").context())
        failures(harness, leak)
        return harness.responses.toList() + afterMove(root, leak)
    }

    private suspend fun failures(harness: MountHarness, leak: String) {
        harness.directory.failure = IllegalStateException(leak)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        harness.directory.failure = null
        harness.directory.hang = true
        harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, OWN), PEER)
        harness.directory.hang = false
        val record = Files.list(harness.root.resolve("canvas-mounts")).use { files ->
            files.filter { it.fileName.toString().startsWith("binding-") }.toList()
        }
        record.forEach { Files.writeString(it, leak) }
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
    }

    private suspend fun afterMove(root: Path, leak: String): List<String> {
        val failing = MountHarness(root, HarnessOptions(hooks = DurableHostFileHooks(afterMove = { throw IOException(leak) })))
        failing.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, OWN), PEER)
        return failing.responses.toList()
    }

    private companion object {
        val EVERY_OUTCOME = listOf(
            CanvasMountWire.FOUND, CanvasMountWire.NONE, CanvasMountWire.BOUND, CanvasMountWire.REPLACED,
            CanvasMountWire.DEFAULT_SET, "denied", "invalid_input", CanvasMountWire.UNAVAILABLE, CanvasMountWire.OUTCOME_UNKNOWN,
        )
    }
}
