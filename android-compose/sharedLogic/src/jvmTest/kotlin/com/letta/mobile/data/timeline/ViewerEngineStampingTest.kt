package com.letta.mobile.data.timeline

import com.letta.mobile.data.runtime.StreamTextFrameSource
import com.letta.mobile.data.runtime.TurnStreamIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-dzt3g: a dialing client (the phone over Iroh, the desktop on the direct route) builds
 * its own `AppServerTurnEngine` on the HOST's wire, and that wire carries CUMULATIVE snapshots.
 * Before #1768 the engine's [TurnStreamIdentity] re-stamped them as increments: "That" then
 * "That points" became "ThatThat points" (the owner's phone screenshot, 2026-10-03). A frame the
 * host already stamped must now reach the timeline as the host sent it.
 */
class ViewerEngineStampingTest {
    @Test fun hostStampedSnapshotsReachADialingEngineAsSent() {
        val stamper = TurnStreamIdentity("turn-1") { "lm-1" }
        val texts = SNAPSHOTS.mapIndexed { index, text ->
            val line = RawWireFrames.line(RawDelta(index + 1, "assistant_message", text, id = "ui-msg-1", stamp = "lm-host" to index + 1))
            val stamped = stamper.stamp(line, StreamTextFrameSource.AppServerDelta) ?: error("dropped")
            Json.parseToJsonElement(stamped).jsonObject["delta"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }
        assertEquals(SNAPSHOTS, texts)
    }

    private companion object {
        val SNAPSHOTS = listOf("That", "That points", "That points at", "That points at the live stream path")
    }
}
