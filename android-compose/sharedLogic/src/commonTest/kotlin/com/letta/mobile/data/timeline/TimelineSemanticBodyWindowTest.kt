package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineSemanticBodyWindowTest {
    private fun reference(bytes: ByteArray) = TimelineBodyReference(
        TimelineScope("backend", "conversation"), TimelinePageKey(1, TimelineMessageId("id")),
        TimelineBodyPointer("digest", bytes.size.toLong()),
        "application/vnd.letta.timeline-event+json;version=1", 1,
    )

    private suspend fun decode(
        json: String, chunk: Int = 7, offset: Long = 0, output: Int = 4096,
        field: TimelineSemanticField = TimelineSemanticField.Content,
    ): TimelineSemanticWindowResult {
        val bytes = json.encodeToByteArray()
        val ref = reference(bytes)
        return TimelineSemanticBodyWindow.read(ref, field, offset, TimelineSemanticBudget(maxOutputBytes = output, chunkBytes = chunk)) { at, size ->
            assertTrue(size <= chunk)
            TimelineBodyChunk(ref, at, bytes.copyOfRange(at.toInt(), minOf(bytes.size, at.toInt() + size)))
        }
    }

    @Test fun unicodeEscapesAndUtf8SurviveEveryChunkBoundary() = runTest {
        val json = """{"messageType":"assistant","content":"a\n\uD83D\uDE00\"\\\t\u00e9é😀","unknown":{"content":"ignore"}}"""
        val expected = "a\n\uD83D\uDE00\"\\\t\u00e9\u00e9\uD83D\uDE00"
        for (size in 1..json.encodeToByteArray().size) {
            val result = assertIs<TimelineSemanticWindowResult.Text>(decode(json, size))
            assertEquals(expected, result.value)
            assertEquals(expected.encodeToByteArray().size, result.outputBytes)
            assertEquals(null, result.nextScalarOffset)
        }
    }

    @Test fun windowsDoNotSplitScalarsOrRetainPriorText() = runTest {
        val json = """{"messageType":"assistant","content":"😀abcdé"}"""
        val first = assertIs<TimelineSemanticWindowResult.Text>(decode(json, output = 4))
        assertEquals("\uD83D\uDE00", first.value)
        assertEquals(1L, first.nextScalarOffset)
        val second = assertIs<TimelineSemanticWindowResult.Text>(decode(json, offset = 1, output = 4))
        assertEquals("abcd", second.value)
        assertEquals(5L, second.nextScalarOffset)
        val last = assertIs<TimelineSemanticWindowResult.Text>(decode(json, offset = 5, output = 4))
        assertEquals("\u00e9", last.value)
        assertEquals(null, last.nextScalarOffset)
    }

    @Test fun hugeFieldsRespectInputAndOperationBudgets() = runTest {
        for (mib in listOf(1, 2, 4, 8)) {
            val json = "{\"messageType\":\"tool_return\",\"attachments\":[{\"thumbnailBase64\":\"" + "x".repeat(mib * 1024 * 1024) + "\"}],\"toolReturnContent\":\"abcdefghi\"}"
            val result = decode(json, chunk = 16 * 1024, output = 4, field = TimelineSemanticField.ToolReturn)
            if (mib == 1) {
                val text = assertIs<TimelineSemanticWindowResult.Text>(result)
                assertEquals("abcd", text.value)
                assertEquals(4, text.outputBytes)
                assertEquals(json.length.toLong(), text.inputBytes)
                assertEquals((json.length + 16383) / 16384, text.reads)
            } else assertEquals(TimelineSemanticWindowResult.Reason.InputBudget, assertIs<TimelineSemanticWindowResult.Deferred>(result).reason)
        }
    }

    @Test fun malformedUnknownAndStructuralLimitsStayExplicit() = runTest {
        for (json in listOf("{\"content\":\"\\uD800x\"}", "{\"content\":\"\\q\"}", "{\"content\":\"ok\",}",
            "{\"content\":\"ok\"} garbage", "{\"content\":\"a\",\"content\":\"b\"}", "{\"content\":true}")) {
            assertEquals(TimelineSemanticWindowResult.Reason.Malformed, assertIs<TimelineSemanticWindowResult.Deferred>(decode(json)).reason)
        }
        assertEquals(TimelineSemanticWindowResult.Reason.MissingField, assertIs<TimelineSemanticWindowResult.Deferred>(decode("{\"messageType\":\"assistant\",\"unknown\":[{\"content\":\"nested\"}]}" )).reason)
        assertEquals(TimelineSemanticWindowResult.Reason.StructuralLimit, assertIs<TimelineSemanticWindowResult.Deferred>(decode("{\"unknown\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}")).reason)
        assertEquals(TimelineSemanticWindowResult.Reason.StructuralLimit, assertIs<TimelineSemanticWindowResult.Deferred>(decode("{\"" + "x".repeat(129) + "\":1}")).reason)
    }

    @Test fun productionShapeAndUnknownDiscriminators() = runTest {
        val stored = com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent(
            position = 1.0, otid = "otid", content = "semantic text", serverId = "id",
            messageType = "reasoning", dateIso = "2026-01-01T00:00:00Z",
        )
        val json = kotlinx.serialization.json.Json.encodeToString(
            com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(), stored,
        )
        assertEquals(stored.content, assertIs<TimelineSemanticWindowResult.Text>(decode(json)).value)
        // What the ledger actually holds. TimelineSnapshotCodec writes messageType as the enum
        // NAME, so a stored body says TOOL_CALL where the wire says tool_call. Reading the wire
        // form only is why every deferred body came back UnsupportedType and large tool output
        // could never be rehydrated.
        for (type in TimelineMessageType.entries.filter { it.name.lowercase() in setOf(
            "user", "assistant", "reasoning", "tool_call", "tool_return",
        ) }) {
            val asStored = com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent(
                position = 1.0, otid = "otid", content = "semantic text", serverId = "id",
                messageType = type.name, dateIso = "2026-01-01T00:00:00Z",
            )
            val encoded = kotlinx.serialization.json.Json.encodeToString(
                com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(), asStored,
            )
            assertEquals(
                asStored.content,
                assertIs<TimelineSemanticWindowResult.Text>(decode(encoded)).value,
                "a body stored as ${type.name} must still be readable",
            )
        }
        for (body in listOf("{\"content\":\"future\"}", "{\"messageType\":\"future_protocol_record\",\"content\":\"future\"}")) {
            assertEquals(TimelineSemanticWindowResult.Reason.UnsupportedType, assertIs<TimelineSemanticWindowResult.Deferred>(decode(body)).reason)
        }
        for (key in listOf("x".repeat(129), "x".repeat(127) + "\uD83D\uDE00")) {
            assertEquals(TimelineSemanticWindowResult.Reason.StructuralLimit,
                assertIs<TimelineSemanticWindowResult.Deferred>(decode("{\"nested\":{\"$key\":1}}")).reason)
        }
    }

    @Test fun rawMalformedUtf8AndReadBudget() = runTest {
        for (bad in listOf(byteArrayOf(0xC0.toByte(), 0x80.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))) {
            val bytes = "{\"content\":\"".encodeToByteArray() + bad + "\"}".encodeToByteArray()
            val ref = reference(bytes)
            val result = TimelineSemanticBodyWindow.read(ref, TimelineSemanticField.Content) { at, size -> TimelineBodyChunk(ref, at, bytes.copyOfRange(at.toInt(), minOf(bytes.size, at.toInt() + size))) }
            assertEquals(TimelineSemanticWindowResult.Reason.Malformed, assertIs<TimelineSemanticWindowResult.Deferred>(result).reason)
        }
        val bytes = "{\"messageType\":\"assistant\",\"content\":\"abcd\"}".encodeToByteArray()
        val ref = reference(bytes)
        var calls = 0
        val result = TimelineSemanticBodyWindow.read(ref, TimelineSemanticField.Content,
            budget = TimelineSemanticBudget(chunkBytes = bytes.size, maxReads = 1)) { at, _ ->
            calls++
            TimelineBodyChunk(ref, at, bytes.copyOfRange(at.toInt(), at.toInt() + 1))
        }
        assertEquals(1, calls)
        assertEquals(TimelineSemanticWindowResult.Reason.ReadBudget, assertIs<TimelineSemanticWindowResult.Deferred>(result).reason)
        val exact = TimelineSemanticBodyWindow.read(ref, TimelineSemanticField.Content,
            budget = TimelineSemanticBudget(maxInputBytes = bytes.size.toLong(), maxOutputBytes = 4, chunkBytes = bytes.size, maxReads = 1)) { at, _ -> TimelineBodyChunk(ref, at, bytes) }
        assertEquals(1, assertIs<TimelineSemanticWindowResult.Text>(exact).reads)
    }

    @Test fun inputBudgetRejectsBeforeReadingAndCancellationPropagates() = runTest {
        val bytes = "{\"content\":\"text\"}".encodeToByteArray()
        val ref = reference(bytes)
        val result = TimelineSemanticBodyWindow.read(ref, TimelineSemanticField.Content, budget = TimelineSemanticBudget(maxInputBytes = 1)) { _, _ -> error("must not read") }
        assertEquals(TimelineSemanticWindowResult.Reason.InputBudget, assertIs<TimelineSemanticWindowResult.Deferred>(result).reason)
        assertFailsWith<CancellationException> {
            TimelineSemanticBodyWindow.read(ref, TimelineSemanticField.Content) { _, _ -> throw CancellationException("cancelled") }
        }
        assertFailsWith<IllegalStateException> {
            TimelineSemanticBodyWindow.read(ref, TimelineSemanticField.Content) { at, _ -> TimelineBodyChunk(ref.copy(revision = 2), at, bytes) }
        }
    }
}
