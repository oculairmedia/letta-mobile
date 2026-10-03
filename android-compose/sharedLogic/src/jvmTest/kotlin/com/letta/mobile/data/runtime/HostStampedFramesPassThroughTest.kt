package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-4vtng: a client that runs its own turn processor over the Iroh host (the desktop's
 * [AppServerTurnEngine] on `IrohAppServerTransport`) receives frames the host has ALREADY stamped:
 * cumulative text, `logical_message_id`, `turn_id`, `text_seq`. Replays the real host output
 * captured on the post-#1761 build (`phone-garble-2026-10-03/same-build-capture.wire-viewer.jsonl`)
 * through a second [TurnStreamIdentity] the way the desktop engine does, and requires every frame
 * to come out byte-for-byte as the host sent it, whichever payload kind carries it.
 */
class HostStampedFramesPassThroughTest {

    @Test
    fun hostStampedRemoteStreamFramesPassThroughUnchanged() {
        assertPassThrough { body -> RuntimeEventPayload.RemoteStreamFrame(frameId = "f", body = body) }
    }

    @Test
    fun hostStampedExternalTransportFramesPassThroughUnchanged() {
        assertPassThrough { body -> RuntimeEventPayload.ExternalTransportFrame(frameId = "f", body = body) }
    }

    @Test
    fun aReplayedStampedTextFrameIsStillDroppedByItsIdempotencyKey() {
        val identity = TurnStreamIdentity("desktop-turn") { "lm-desktop" }
        val frame = hostFrames().first { it.delta().str("message_type") == "assistant_message" }.toString()
        assertEquals(frame, identity.stamp(frame, StreamTextFrameSource.AppServerDelta))
        assertEquals(null, identity.stamp(frame, StreamTextFrameSource.AppServerDelta))
    }

    private fun assertPassThrough(payloadOf: (String) -> RuntimeEventPayload) {
        val identity = TurnStreamIdentity("desktop-turn") { "lm-desktop" }
        val frames = hostFrames()
        val textFrames = frames.count { it.delta().str("message_type") in TEXT_TYPES }
        assertTrue(textFrames > 50, "the capture holds the host's stamped text frames ($textFrames)")
        frames.forEach { frame ->
            val out = identity.stampPayload(payloadOf(frame.toString()))
            val body = when (out) {
                is RuntimeEventPayload.RemoteStreamFrame -> out.body
                is RuntimeEventPayload.ExternalTransportFrame -> out.body
                else -> error("unexpected $out")
            }
            assertEquals(frame, AppServerProtocol.json.parseToJsonElement(body).jsonObject)
        }
    }

    private fun hostFrames(): List<JsonObject> =
        resource("phone-garble-2026-10-03/same-build-capture.wire-viewer.jsonl").lines().filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject.getValue("wire").jsonObject }
            .filter { it.str("type") == "stream_delta" && it.delta().containsKey("logical_message_id") }

    private fun JsonObject.delta(): JsonObject = getValue("delta").jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/timeline/identity/real-turns/$path")) { "missing $path" }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private companion object {
        val TEXT_TYPES = setOf("assistant_message", "reasoning_message", "hidden_reasoning_message")
    }
}
