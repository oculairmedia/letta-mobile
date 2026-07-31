package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-lgns8.21.7 regression tests. Every case here fails (or does not
 * compile / overflows the stack) against the pre-fix inspector, which walked
 * whole messages with an unbounded recursive DFS.
 */
class AppServerPreflightInspectionTest {

    @Test
    fun oversizedAttachmentIsSizedNotScanned() {
        // ~4 MB base64-ish content field. The pre-fix probe called
        // contentOrNull.isNullOrBlank() on it and DFS'd the envelope; the bounded
        // inspector charges at most MAX_SCANNED_STRING_CHARS for it.
        val huge = "A".repeat(4 * 1024 * 1024)
        val message = buildJsonObject {
            put("id", "msg-huge")
            put("role", "assistant")
            put("content", huge)
        }
        val inspector = BoundedMessageInspector()

        val probe = inspector.inspect(message)

        assertEquals("msg-huge", probe.id)
        assertEquals("assistant", probe.role)
        assertFalse(probe.hasEmptyAssistant, "a multi-megabyte content field is not empty content")
        assertTrue(probe.bounded, "oversized field must be reported as a bound hit")
        assertTrue(
            inspector.bytesVisited < AppServerPreflightBounds.MAX_MESSAGE_INSPECTED_BYTES,
            "bytesVisited=${inspector.bytesVisited} must stay under the per-message budget",
        )
        assertTrue(inspector.summary().anyBoundHit)
    }

    @Test
    fun cumulativePageBudgetStopsInspectionEarly() {
        // 200 rows, each ~64 KiB of *inspectable* fields (short strings inside a
        // walked envelope), i.e. ~12 MB of inspection work if unbounded.
        val rows = (0 until 200).map { index -> wideMessage("msg-$index", fields = 400, keyChars = 170) }
        val inspector = BoundedMessageInspector()

        var inspected = 0
        for (row in rows) {
            if (inspector.budgetExhausted) break
            inspector.inspect(row)
            inspected++
        }
        inspector.skipRemaining(rows.size - inspected)

        val summary = inspector.summary()
        assertTrue(summary.budgetExhausted, "the page budget must be reached by this fixture")
        assertTrue(inspected < rows.size, "inspection must stop before the whole page (inspected=$inspected)")
        assertTrue(summary.messagesSkipped > 0)
        assertTrue(
            summary.bytesVisited < AppServerPreflightBounds.MAX_INSPECTED_BYTES.toLong() +
                AppServerPreflightBounds.MAX_MESSAGE_INSPECTED_BYTES.toLong(),
            "bytesVisited=${summary.bytesVisited} must stay within one message of the page budget",
        )
        assertTrue(summary.anyBoundHit)
    }

    @Test
    fun deeplyNestedEnvelopeDoesNotBlowTheDfs() {
        // The pre-fix hasProviderLengthStop recursed once per nesting level with
        // no depth bound: this fixture is a StackOverflowError against it.
        var nested: JsonElement = buildJsonObject { put("stop_reason", "length") }
        repeat(DEEP_NESTING_LEVELS) {
            nested = JsonObject(mapOf("message" to nested))
        }
        val message = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg-deep"),
                "role" to JsonPrimitive("assistant"),
                "message" to nested,
            ),
        )
        val inspector = BoundedMessageInspector()

        val probe = inspector.inspect(message)

        // Bounded: the length stop is buried far below MAX_INSPECTION_DEPTH, so
        // it is NOT found — and the pass reports that it degraded.
        assertFalse(probe.hasLengthStop)
        assertTrue(probe.bounded, "depth bound must be reported")
        assertTrue(inspector.summary().anyBoundHit)
    }

    @Test
    fun wideFanOutIsNodeBounded() {
        val fanOut = JsonArray(
            (0 until 50_000).map { buildJsonObject { put("role", "assistant") } },
        )
        val message = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg-wide"),
                "role" to JsonPrimitive("assistant"),
                "message" to fanOut,
            ),
        )
        val inspector = BoundedMessageInspector()

        inspector.inspect(message)

        val summary = inspector.summary()
        assertTrue(summary.nodeBoundHits > 0 || summary.messageByteBoundHits > 0)
        assertTrue(
            summary.bytesVisited < AppServerPreflightBounds.MAX_MESSAGE_INSPECTED_BYTES.toLong() * 2,
            "bytesVisited=${summary.bytesVisited} must stay bounded on a 50k-node fan-out",
        )
    }

    @Test
    fun normalEnvelopeIsUnaffectedByTheBounds() {
        val message = buildJsonObject {
            put("id", "msg-ok")
            put("role", "assistant")
            put("content", "hello")
            put(
                "provider_result",
                buildJsonObject {
                    put("stopReason", "length")
                    put("usage", buildJsonObject { put("input", 128_000) })
                },
            )
        }
        val inspector = BoundedMessageInspector()

        val probe = inspector.inspect(message)

        assertEquals("msg-ok", probe.id)
        assertEquals("assistant", probe.role)
        assertTrue(probe.hasLengthStop)
        assertEquals(128_000L, probe.inputTokens)
        assertFalse(probe.bounded, "a normal envelope must not trip any bound")
        assertFalse(inspector.summary().anyBoundHit)
    }

    private fun wideMessage(id: String, fields: Int, keyChars: Int): JsonObject {
        // Inspection cost is driven by the fields the walker actually reads
        // (keys + short scalars), never by opaque payload values — so the
        // fixture makes the READ surface large.
        val filler = "k".repeat(keyChars)
        val payload = buildJsonObject {
            for (index in 0 until fields) put("$filler$index", "v")
        }
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "role" to JsonPrimitive("assistant"),
                "content" to JsonPrimitive("ok"),
                "message" to payload,
            ),
        )
    }

    private companion object {
        /** Deep enough that an unbounded recursive DFS overflows every runtime. */
        const val DEEP_NESTING_LEVELS = 30_000
    }
}
