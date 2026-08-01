package com.letta.mobile.data.repository

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryDelegate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * letta-mobile-z5lqt: roster/name telemetry.
 *
 * Assertions are on EVENT ATTRIBUTES via the telemetry ring, never on log
 * strings, so a formatting change cannot silently pass a broken classifier.
 *
 * Backtick test names deliberately contain no commas and no parentheses:
 * both are legal on JVM but illegal in Kotlin/Native.
 */
class RosterNameTelemetryTest {

    private object SilentDelegate : TelemetryDelegate {
        override fun logToLogcat(level: Telemetry.Level, tag: String, body: String, throwable: Throwable?) = Unit
        override fun isLoggable(tag: String, level: Int): Boolean = false
        override fun isTraceEnabled(): Boolean = false
        override fun beginSection(name: String) = Unit
        override fun endSection() = Unit
        override fun beginAsyncSection(name: String, cookie: Int) = Unit
        override fun endAsyncSection(name: String, cookie: Int) = Unit
    }

    @BeforeTest
    fun setUp() {
        Telemetry.delegate = SilentDelegate
        Telemetry.clear()
    }

    @AfterTest
    fun tearDown() {
        Telemetry.delegate = null
        Telemetry.clear()
    }

    private fun lastEvent(name: String): Telemetry.Event =
        Telemetry.snapshot().first { it.name == name }

    private fun eventsNamed(name: String): List<Telemetry.Event> =
        Telemetry.snapshot().filter { it.name == name }

    // --- Roster completeness invariant: three distinguishable outcomes ---

    @Test
    fun `swept roster equal to authoritative count classifies as match`() {
        val outcome = RosterNameTelemetry.classifyCompleteness(sweptSize = 131, authoritativeCount = 131)
        assertTrue(outcome is RosterNameTelemetry.Completeness.Match)

        RosterNameTelemetry.rosterCompleteness(outcome, source = "test")
        val ev = lastEvent("roster.completeness")
        assertEquals("match", ev.attrs["completeness"])
        assertEquals(131, ev.attrs["sweptSize"])
        assertEquals(131, ev.attrs["authoritativeCount"])
        assertEquals(Telemetry.Level.INFO, ev.level)
    }

    @Test
    fun `truncated roster against authoritative count classifies as mismatch`() {
        val outcome = RosterNameTelemetry.classifyCompleteness(sweptSize = 50, authoritativeCount = 131)
        assertTrue(outcome is RosterNameTelemetry.Completeness.Mismatch)

        RosterNameTelemetry.rosterCompleteness(outcome, source = "test")
        val ev = lastEvent("roster.completeness")
        assertEquals("mismatch", ev.attrs["completeness"])
        assertEquals(50, ev.attrs["sweptSize"])
        assertEquals(131, ev.attrs["authoritativeCount"])
        assertEquals(-81, ev.attrs["delta"])
        assertEquals(Telemetry.Level.WARN, ev.level)
    }

    @Test
    fun `absent authoritative count classifies as unknown and never as match`() {
        val outcome = RosterNameTelemetry.classifyCompleteness(sweptSize = 50, authoritativeCount = null)
        assertTrue(outcome is RosterNameTelemetry.Completeness.Unknown)
        assertTrue(outcome !is RosterNameTelemetry.Completeness.Match)
        assertTrue(outcome !is RosterNameTelemetry.Completeness.Mismatch)

        RosterNameTelemetry.rosterCompleteness(outcome, source = "test")
        val ev = lastEvent("roster.completeness")
        assertEquals("unknown", ev.attrs["completeness"])
        assertEquals("unknown", ev.attrs["authoritativeCount"])
        assertEquals(RosterNameTelemetry.UnknownReason.COUNT_UNAVAILABLE, ev.attrs["unknownReason"])
        assertEquals(Telemetry.Level.WARN, ev.level)
    }

    @Test
    fun `failed count rpc classifies as unknown with a failure reason`() {
        val outcome = RosterNameTelemetry.classifyCompleteness(
            sweptSize = 50,
            authoritativeCount = Result.failure(IllegalStateException("rpc down")),
        )
        assertTrue(outcome is RosterNameTelemetry.Completeness.Unknown)

        RosterNameTelemetry.rosterCompleteness(outcome, source = "test")
        val ev = lastEvent("roster.completeness")
        assertEquals("unknown", ev.attrs["completeness"])
        assertEquals(RosterNameTelemetry.UnknownReason.COUNT_FAILED, ev.attrs["unknownReason"])
        assertNull(ev.attrs["delta"])
    }

    @Test
    fun `unknown count that happens to equal zero swept size is still unknown`() {
        // Guards the exact defect class: an unmeasurable window must never
        // read as agreement just because the numbers could be made to line up.
        val outcome = RosterNameTelemetry.classifyCompleteness(sweptSize = 0, authoritativeCount = null)
        assertTrue(outcome is RosterNameTelemetry.Completeness.Unknown)
        assertEquals("unknown", RosterNameTelemetry.completenessAttrs(outcome).toMap()["completeness"])
    }

    @Test
    fun `negative authoritative count is unknown rather than a mismatch`() {
        val outcome = RosterNameTelemetry.classifyCompleteness(sweptSize = 10, authoritativeCount = -1)
        val unknown = assertIs<RosterNameTelemetry.Completeness.Unknown>(outcome)
        assertEquals(RosterNameTelemetry.UnknownReason.COUNT_INVALID, unknown.reason)
    }

    // --- Sweep stops: all five outcomes individually distinguishable ---

    @Test
    fun `rpc with no result emits a noResult sweep stop`() {
        RosterNameTelemetry.sweepStopped(
            stop = RosterNameTelemetry.SweepStop.NO_RESULT,
            offset = 100,
            pageSize = 0,
            mergedSize = 100,
            source = "test",
        )
        val ev = lastEvent("roster.sweepStopped")
        assertEquals("noResult", ev.attrs["stop"])
        assertEquals(100, ev.attrs["offset"])
        assertEquals(0, ev.attrs["pageSize"])
        assertEquals(100, ev.attrs["mergedSize"])
        assertEquals(Telemetry.Level.INFO, ev.level)
    }

    @Test
    fun `empty page emits an emptyPage sweep stop`() {
        RosterNameTelemetry.sweepStopped(
            stop = RosterNameTelemetry.SweepStop.EMPTY_PAGE,
            offset = 150,
            pageSize = 0,
            mergedSize = 131,
            source = "test",
        )
        val ev = lastEvent("roster.sweepStopped")
        assertEquals("emptyPage", ev.attrs["stop"])
        assertEquals(150, ev.attrs["offset"])
        assertEquals(131, ev.attrs["mergedSize"])
    }

    @Test
    fun `server ignoring offset emits a distinct noFreshIgnoredOffset stop`() {
        RosterNameTelemetry.sweepStopped(
            stop = RosterNameTelemetry.SweepStop.NO_FRESH_IGNORED_OFFSET,
            offset = 50,
            pageSize = 50,
            mergedSize = 50,
            source = "test",
        )
        val ev = lastEvent("roster.sweepStopped")
        assertEquals("noFreshIgnoredOffset", ev.attrs["stop"])
        // A full page that yielded nothing new is NOT the same as an empty page.
        assertEquals(50, ev.attrs["pageSize"])
        assertTrue(ev.attrs["stop"] != "emptyPage")
    }

    @Test
    fun `short page emits a shortPage sweep stop`() {
        RosterNameTelemetry.sweepStopped(
            stop = RosterNameTelemetry.SweepStop.SHORT_PAGE,
            offset = 100,
            pageSize = 31,
            mergedSize = 131,
            source = "test",
        )
        val ev = lastEvent("roster.sweepStopped")
        assertEquals("shortPage", ev.attrs["stop"])
        assertEquals(31, ev.attrs["pageSize"])
        assertEquals(Telemetry.Level.INFO, ev.level)
    }

    @Test
    fun `page cap exhaustion emits a pageCapExhausted stop at warn level`() {
        RosterNameTelemetry.sweepStopped(
            stop = RosterNameTelemetry.SweepStop.PAGE_CAP_EXHAUSTED,
            offset = 2500,
            pageSize = 50,
            mergedSize = 2500,
            source = "test",
        )
        val ev = lastEvent("roster.sweepStopped")
        assertEquals("pageCapExhausted", ev.attrs["stop"])
        assertEquals(Telemetry.Level.WARN, ev.level)
    }

    @Test
    fun `every sweep stop has a unique wire name`() {
        val wireNames = RosterNameTelemetry.SweepStop.entries.map { it.wireName }
        assertEquals(5, wireNames.size)
        assertEquals(wireNames.size, wireNames.toSet().size)
    }

    // --- Cache miss ---

    @Test
    fun `cache miss records the agent id and the roster size it missed against`() {
        RosterNameTelemetry.cacheMiss(agentId = "agent-abcdef12", cacheSize = 50, source = "AgentRepository")
        val ev = lastEvent("agentCache.miss")
        assertEquals("agent-abcdef12", ev.attrs["agentId"])
        assertEquals(50, ev.attrs["cacheSize"])
        assertEquals("AgentRepository", ev.attrs["source"])
        assertEquals(Telemetry.Level.WARN, ev.level)
    }

    // --- Name fallbacks ---

    @Test
    fun `conversation list id prefix fallback is recorded with its site`() {
        RosterNameTelemetry.nameFallback(
            site = RosterNameTelemetry.NameFallbackSite.CONVERSATION_LIST,
            agentId = "agent-abcdef12",
            fallbackKind = RosterNameTelemetry.FallbackKind.ID_PREFIX,
            rosterSize = 50,
        )
        val ev = lastEvent("name.fallback")
        assertEquals("conversationList", ev.attrs["site"])
        assertEquals("idPrefix", ev.attrs["fallbackKind"])
        assertEquals(50, ev.attrs["rosterSize"])
        assertEquals(Telemetry.Level.WARN, ev.level)
    }

    @Test
    fun `chat coordinator previous name fallback is recorded with its site`() {
        RosterNameTelemetry.nameFallback(
            site = RosterNameTelemetry.NameFallbackSite.CHAT_COORDINATOR,
            agentId = "agent-abcdef12",
            fallbackKind = RosterNameTelemetry.FallbackKind.PREVIOUS_UI_NAME,
            rosterSize = 50,
        )
        val ev = lastEvent("name.fallback")
        assertEquals("chatCoordinator", ev.attrs["site"])
        assertEquals("previousUiName", ev.attrs["fallbackKind"])
    }

    @Test
    fun `concurrent cache miss resolvers join one request and reuse success`() = runTest {
        val release = CompletableDeferred<String?>()
        var fetches = 0
        val resolver = RosterNameResolver(
            fetch = { _: String ->
                fetches += 1
                release.await()
            },
            source = "test",
        )

        val first = async { resolver.resolve("agent-1") }
        runCurrent()
        val second = async { resolver.resolve("agent-1") }
        runCurrent()
        assertEquals(1, fetches)

        release.complete("Ada")
        runCurrent()
        assertEquals("Ada", first.await())
        assertEquals("Ada", second.await())
        assertEquals("Ada", resolver.resolve("agent-1"))
        assertEquals(1, fetches)
        val event = lastEvent("name.resolveOutcome")
        assertEquals("success", event.attrs["outcome"])
    }

    @Test
    fun `not found and failure resolve outcomes are loud`() = runTest {
        RosterNameResolver<String>({ null }, "test").resolve("missing")
        RosterNameResolver<String>({ error("down") }, "test").resolve("failed")

        val outcomes = eventsNamed("name.resolveOutcome").associateBy { it.attrs["agentId"] }
        assertEquals("notFound", outcomes["missing"]?.attrs?.get("outcome"))
        assertEquals(Telemetry.Level.WARN, outcomes["missing"]?.level)
        assertEquals("failure", outcomes["failed"]?.attrs?.get("outcome"))
        assertEquals(Telemetry.Level.WARN, outcomes["failed"]?.level)
    }

    @Test
    fun `the two name fallback sites stay distinguishable`() {
        RosterNameTelemetry.nameFallback(
            site = RosterNameTelemetry.NameFallbackSite.CONVERSATION_LIST,
            agentId = "a",
            fallbackKind = RosterNameTelemetry.FallbackKind.ID_PREFIX,
            rosterSize = 1,
        )
        RosterNameTelemetry.nameFallback(
            site = RosterNameTelemetry.NameFallbackSite.CHAT_COORDINATOR,
            agentId = "a",
            fallbackKind = RosterNameTelemetry.FallbackKind.PREVIOUS_UI_NAME,
            rosterSize = 1,
        )
        val sites = eventsNamed("name.fallback").map { it.attrs["site"] }.toSet()
        assertEquals(setOf("conversationList", "chatCoordinator"), sites)
    }
}
