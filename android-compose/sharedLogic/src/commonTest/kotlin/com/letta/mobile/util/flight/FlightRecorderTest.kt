package com.letta.mobile.util.flight

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The flight record keeps connection, turn and feed state within its ceiling, never content, and never writes on the caller. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightRecorderTest {
    /** Counts what reaches it, so a test sees when (and whether) the recorder wrote. */
    private class CountingStore(private val inner: FlightRecordStore = InMemoryFlightRecordStore(1L shl 20)) : FlightRecordStore {
        var appends = 0
        override fun append(lines: List<String>) {
            appends += 1
            inner.append(lines)
        }
        override fun read(): List<String> = inner.read()
    }

    private fun event(
        name: String,
        timestampMs: Long,
        vararg attrs: Pair<String, TelemetryValue>,
        tag: String = "FeedTimeline",
    ) = Telemetry.Event(timestampMs, tag, name, durationMs = null, attrs = attrs.toMap(), level = Telemetry.Level.INFO)

    private fun floodLine(index: Int) = "line ${index.toString().padStart(5, '0')} of a flood"

    @Test
    fun theMemoryRingStaysWithinItsCeilingUnderAFloodAndKeepsTheNewest() {
        val ceiling = 4_096L
        val store = InMemoryFlightRecordStore(ceiling)

        (1..10_000).forEach { store.append(listOf(floodLine(it))) }

        val kept = store.read()
        val bytes = kept.sumOf { it.encodeToByteArray().size + 1L }
        assertTrue(bytes <= ceiling, "kept $bytes bytes, ceiling $ceiling")
        assertTrue(kept.size > 10, "the ring still holds a useful tail, held ${kept.size}")
        assertEquals(floodLine(10_000), kept.last())
        assertEquals(kept.sorted(), kept, "oldest first, no gaps reordered")
        assertEquals((10_001 - kept.size..10_000).map(::floodLine), kept, "eviction took only the oldest")
    }

    @Test
    fun offeringAnEventNeverWritesOnTheCallersThread() = runTest {
        val store = CountingStore()
        val recorder = FlightRecorder(store, backgroundScope, flushDelayMs = 1_000)
        runCurrent()

        recorder.offer(event("applied", 1, "seq" to TelemetryValue.Numeric(7)))

        assertEquals(0, store.appends, "offer only queues: the write happens later, on the recorder's own coroutine")
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, store.appends)
        assertTrue(store.read().single().contains("seq=7"))
    }

    @Test
    fun aBurstIsOneWriteAndARepeatedEventIsRateLimitedWithACount() = runTest {
        val store = CountingStore()
        val recorder = FlightRecorder(store, backgroundScope, flushDelayMs = 1_000)
        runCurrent()

        repeat(500) { recorder.offer(event("applied", it.toLong(), "seq" to TelemetryValue.Numeric(it))) }
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(1, store.appends, "everything waiting goes in one write")
        assertEquals(10, store.read().size, "ten lines per key per window; the rest are counted, not written")
        recorder.offer(event("applied", 20_000, "seq" to TelemetryValue.Numeric(999)))
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(store.read().last().contains("suppressed=490"), store.read().last())
    }

    @Test
    fun debugAndUnlistedEventsAreNotRecordedButWarningsAre() = runTest {
        val store = CountingStore()
        val recorder = FlightRecorder(store, backgroundScope, flushDelayMs = 10)
        runCurrent()

        recorder.offer(event("publication", 1).copy(level = Telemetry.Level.DEBUG))
        recorder.offer(event("frame.recv", 1, tag = "IrohTransport"))
        recorder.offer(event("anything", 1, tag = "SomeScreen").copy(level = Telemetry.Level.WARN))
        advanceTimeBy(11)
        runCurrent()

        assertEquals(listOf("SomeScreen/anything"), store.read().map { it.substringAfter("] ").substringBefore(' ') })
    }

    /**
     * Content keys the record must never carry, written down here independently of the production policy: a recorded line
     * that names one of them fails this test.
     */
    private val denyList = listOf(
        "text", "content", "messageText", "message", "body", "prompt", "arguments", "toolArgs", "result", "toolResult", "output",
        "input", "payload", "delta", "token", "accessToken", "ticket", "secret", "apiKey", "key", "password", "authorization",
        "cookie", "url", "baseShimUrl", "errorMessage", "error",
    )

    @Test
    fun aRecordedEventNeverCarriesAContentKeyOrAThrowableMessage() = runTest {
        val store = CountingStore()
        val recorder = FlightRecorder(store, backgroundScope, flushDelayMs = 10)
        runCurrent()
        val secret = "SECRET-CONTENT-42"
        val content = denyList.map { it to TelemetryValue.Text(secret) }.toTypedArray()

        recorder.offer(
            event(
                "applied", 1, *content, "conversationId" to TelemetryValue.Text("conv-1"), "seq" to TelemetryValue.Numeric(3),
                "framesApplied" to TelemetryValue.Numeric(9),
            ),
        )
        recorder.offer(
            event("crash.caught", 2, *content, tag = "IrohTransport")
                .copy(level = Telemetry.Level.ERROR, throwable = IllegalStateException(secret)),
        )
        advanceTimeBy(11)
        runCurrent()

        val lines = store.read()
        assertEquals(2, lines.size)
        lines.forEach { line ->
            assertTrue(secret !in line, "content leaked into the record: $line")
            denyList.forEach { key -> assertTrue(Regex("[{ ,]$key=").find(line) == null, "deny-listed key $key recorded: $line") }
        }
        assertTrue(lines.first().contains("conversationId=conv-1") && lines.first().contains("seq=3"), lines.first())
        assertTrue(lines.last().contains("errorClass=IllegalStateException"), lines.last())
    }

    @Test
    fun aRecordedLineIsOneLineAndItsTextIsCut() = runTest {
        val store = CountingStore()
        val recorder = FlightRecorder(store, backgroundScope, flushDelayMs = 10)
        runCurrent()

        recorder.offer(event("state", 1, "reason" to TelemetryValue.Text("a\nb" + "x".repeat(500)), tag = "IrohSupervisor"))
        advanceTimeBy(11)
        runCurrent()

        val line = store.read().single()
        assertTrue('\n' !in line)
        assertTrue(line.length < 300, "text attributes are cut, line was ${line.length}")
    }
}
