package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.plugin.api.LcpDirection
import com.letta.mobile.plugin.api.LcpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * The generic golden transcripts of LCP wire v1 replayed against the reference plugin
 * `plugins/chart` where they apply (letta-mobile-cdgpr.2): the host's side of
 * `session-process.jsonl` with `element-event.jsonl` and `host-settings-changed.jsonl` spliced in
 * before the deactivate. Every lifecycle request gets the recorded answer (the handshake: the same
 * `ok` and contract version), every recorded failure the same code; the example plugin's actions
 * that succeed there (`start`) are not the chart plugin's and are skipped. Skipped without `node`.
 */
class ChartPluginFixtureReplayTest {
    @Test
    fun theGenericTranscriptsReplayAgainstTheChartPlugin() {
        if (!ChartPluginProcess.nodeAvailable) {
            println("skipped: node is not on PATH")
            return
        }
        val session = WireTranscripts.lines("session-process.jsonl")
        val spliced = WireTranscripts.lines("element-event.jsonl") + WireTranscripts.lines("host-settings-changed.jsonl")
        val hostLines = session.filter { it.dir == LcpDirection.HOST_TO_PLUGIN }
        val replay = (hostLines.dropLast(1) + spliced + hostLines.last()).filter { applies(it, session) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ChartPluginProcess.start().use { plugin ->
            val binding = LcpHostBinding(ChartPluginProcess.manifest().capabilities.toSet(), PluginSecrets().scrubber(), RecordingHost())
            val host = LcpHostSession(plugin.transport, binding, scope)
            try {
                runBlocking { withTimeout(30.seconds) { replay.forEach { replayLine(host.peer, it.message, session) } } }
            } finally {
                scope.cancel()
            }
            assertEquals(0, plugin.awaitExit(10), "the plugin exits after the recorded plugin.deactivate")
        }
    }

    private suspend fun replayLine(peer: LcpPeer, message: JsonRpcMessage, session: List<WireLine>) {
        when (message) {
            is JsonRpcMessage.Notification -> peer.notify(checkNotNull(LcpMethod.byWire(message.method)), message.params)
            is JsonRpcMessage.Request -> replayRequest(peer, message, answerTo(message, session))
            else -> Unit
        }
    }

    private suspend fun replayRequest(peer: LcpPeer, request: JsonRpcMessage.Request, answer: JsonRpcMessage) {
        val method = checkNotNull(LcpMethod.byWire(request.method))
        when (answer) {
            is JsonRpcMessage.Failure -> assertEquals(answer.error.code, assertFailsWith<LcpCallException> { peer.request(method, request.params) }.code, request.method)
            is JsonRpcMessage.Success -> assertEquals(comparable(method, answer.result), comparable(method, peer.request(method, request.params)), request.method)
            else -> error("no answer to ${request.id}")
        }
    }

    /** The handshake's `pluginInfo` is each plugin's own; the rest of every answer is compared whole. */
    private fun comparable(method: LcpMethod, result: JsonElement) =
        if (method == LcpMethod.INITIALIZE) normalized(JsonObject((result as JsonObject) - "pluginInfo")) else normalized(result)

    /** A notification always applies; a request applies unless it is an action the recording answered with success. */
    private fun applies(line: WireLine, session: List<WireLine>): Boolean {
        val request = line.message as? JsonRpcMessage.Request ?: return true
        return request.method != LcpMethod.INVOKE.wire || answerTo(request, session) is JsonRpcMessage.Failure
    }

    private fun answerTo(request: JsonRpcMessage.Request, session: List<WireLine>): JsonRpcMessage = session
        .filter { it.dir == LcpDirection.PLUGIN_TO_HOST }
        .map { it.message }
        .first { (it as? JsonRpcMessage.Success)?.id == request.id || (it as? JsonRpcMessage.Failure)?.id == request.id }
}
