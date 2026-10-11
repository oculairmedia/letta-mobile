package com.letta.mobile.data.context.estimate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-cyh28: the local-backend context estimator and its calibration to the exact total. */
class ContextBreakdownEstimatorTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // ── calibration ────────────────────────────────────────────────────────

    @Test
    fun aTotalAboveTheSectionsLeavesToolsAsTheResidual() {
        val sections = ContextSections(system = 3_000, memory = 1_000, summary = 500, messages = 10_000)
        val matched = ContextBreakdownEstimator.calibrate(sections, reportedTotal = 20_000)
        assertEquals(sections, matched.sections)
        assertEquals(5_500, matched.tools)
        assertEquals(20_000, matched.total)
        assertFalse(matched.scaledDown)
    }

    @Test
    fun aTotalBelowTheSectionsScalesThemDownAndToolsIsZero() {
        val sections = ContextSections(system = 3_333, memory = 1_111, summary = 0, messages = 7_777)
        val matched = ContextBreakdownEstimator.calibrate(sections, reportedTotal = 10_000)
        assertEquals(0, matched.tools)
        assertTrue(matched.scaledDown)
        assertEquals(10_000, matched.sections.total)
        assertEquals(0, matched.sections.summary)
    }

    @Test
    fun theRowsAlwaysSumToTheReportedTotal() {
        val random = Random(42)
        repeat(500) {
            val sections = ContextSections(
                random.nextInt(0, 50_000), random.nextInt(0, 20_000), random.nextInt(0, 5_000), random.nextInt(0, 150_000),
            )
            val total = random.nextInt(0, 250_000)
            val matched = ContextBreakdownEstimator.calibrate(sections, total)
            assertEquals(total, matched.sections.total + (matched.tools ?: 0), "sections=$sections total=$total")
            assertTrue(matched.sections.asList().all { it >= 0 } && (matched.tools ?: 0) >= 0)
        }
    }

    @Test
    fun withoutATotalNothingIsDerived() {
        val sections = ContextSections(1, 2, 3, 4)
        val matched = ContextBreakdownEstimator.calibrate(sections, reportedTotal = null)
        assertNull(matched.tools)
        assertEquals(10, matched.total)
    }

    // ── per-message formula (letta-code estimateLocalMessageTokens) ─────────

    @Test
    fun messagesCountLikeLettaCode() {
        assertEquals(3, LocalMessageTokens.of(obj("""{"role":"user","content":"hello world"}""")))
        val assistant = obj(
            """{"role":"assistant","content":[{"type":"text","text":"abcd"},{"type":"thinking","thinking":"efgh"},
               {"type":"toolCall","name":"Bash","arguments":{"cmd":"ls"}}]}""",
        )
        // 4 + 4 + len("Bash") + len({"cmd":"ls"}) = 4 + 4 + 4 + 12 = 24 chars -> 6 tokens.
        assertEquals(6, LocalMessageTokens.of(assistant))
        val image = obj("""{"role":"user","content":[{"type":"image","data":"x"}]}""")
        assertEquals(LocalMessageTokens.IMAGE_TOKENS, LocalMessageTokens.of(image))
        assertEquals(2, LocalMessageTokens.of(obj("""{"role":"user","parts":[{"type":"text","text":"hey!x"}]}""")))
        assertEquals(0, LocalMessageTokens.of(obj("""{"role":"toolResult"}""")))
    }

    // ── transcript: what is in context ─────────────────────────────────────

    @Test
    fun aSessionCompactionKeepsTheSummaryTheKeptTailAndWhatFollows() {
        val transcript = LocalTranscriptContext.read(
            sequenceOf(
                """{"type":"session","version":1}""",
                """{"type":"message","id":"e1","message":{"id":"m1","role":"user","content":"old one"}}""",
                """{"type":"message","id":"e2","message":{"id":"m2","role":"assistant","content":[{"type":"text","text":"kept"}],"usage":{"totalTokens":90000},"timestamp":10}}""",
                """{"type":"compaction","id":"e3","firstKeptEntryId":"e2","summary":"s",
                   "message":{"id":"m3","role":"user","content":"summary text","timestamp":20,"metadata":{"compaction":{"summary":"s"}}}}""",
                """{"type":"message","id":"e4","message":{"id":"m4","role":"user","content":"new"}}""",
                "not json",
            ),
        )
        assertEquals("m3", transcript.summary?.get("id")?.jsonPrimitive?.content)
        assertEquals(listOf("m2", "m4"), transcript.messages.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(1, transcript.firstPostCompaction)
        // The kept reply's usage predates the compaction: stale.
        assertNull(transcript.recordedTotal())
    }

    @Test
    fun aBareTranscriptStartsAgainAtItsCompactionSummary() {
        val transcript = LocalTranscriptContext.read(
            sequenceOf(
                """{"id":"m1","role":"user","content":"before"}""",
                """{"id":"m2","role":"user","content":"Note: prior messages...","metadata":{"compaction":{"summary":"s"}}}""",
                """{"id":"m3","role":"user","content":"after"}""",
            ),
        )
        assertEquals("m2", transcript.summary?.get("id")?.jsonPrimitive?.content)
        assertEquals(listOf("m3"), transcript.messages.map { it.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun theRecordedTotalIsTheLastFreshUsagePlusWhatFollowed() {
        val transcript = LocalTranscriptContext.read(
            sequenceOf(
                """{"id":"m1","role":"assistant","content":[],"usage":{"input":100,"output":20,"cacheRead":30000}}""",
                """{"id":"m2","role":"assistant","content":[],"stopReason":"aborted","usage":{"totalTokens":99999}}""",
                """{"id":"m3","role":"user","content":"12345678"}""",
            ),
        )
        assertEquals(30_120 + 2, transcript.recordedTotal())
    }

    // ── the whole estimate ──────────────────────────────────────────────────

    @Test
    fun coreMemoryIsSplitOutOfTheSystemPromptWhenTheSidecarHasIt() {
        val estimate = ContextBreakdownEstimator.estimate(
            inputs(sidecar = """{"content":"${"s".repeat(400)}${"m".repeat(200)}","coreMemory":"${"m".repeat(200)}"}"""),
        )
        assertEquals(100, estimate.sections.system)
        assertEquals(50, estimate.sections.memory)
        assertTrue(estimate.memorySplit)
    }

    @Test
    fun aSidecarWithoutCoreMemoryCountsItAllAsSystem() {
        val estimate = ContextBreakdownEstimator.estimate(inputs(sidecar = """{"content":"${"s".repeat(600)}"}"""))
        assertEquals(150, estimate.sections.system)
        assertEquals(0, estimate.sections.memory)
        assertFalse(estimate.memorySplit)
    }

    @Test
    fun theClientTotalWinsOverTheRecordedOneAndTheJsonSaysHowItWasMade() {
        val estimate = ContextBreakdownEstimator.estimate(
            inputs(
                sidecar = """{"content":"${"s".repeat(400)}"}""",
                lines = listOf("""{"id":"a","role":"assistant","content":[],"usage":{"totalTokens":5000}}"""),
                reportedTotal = 8_000,
            ),
        )
        assertEquals(ContextTotalSource.Client, estimate.totalSource)
        assertEquals(8_000, estimate.total)
        assertEquals(8_000 - 100, estimate.tools)

        val json = estimate.toAgentContextJson()
        assertEquals("estimate", json.getValue("source").jsonPrimitive.content)
        assertTrue(json.getValue("calibrated").jsonPrimitive.boolean)
        assertTrue(json.getValue("tools_derived").jsonPrimitive.boolean)
        assertEquals("client", json.getValue("total_source").jsonPrimitive.content)
        assertEquals(8_000, json.getValue("context_window_size_current").jsonPrimitive.int)
    }

    @Test
    fun withNoTotalAnywhereTheAnswerIsPartialAndSaysSo() {
        val estimate = ContextBreakdownEstimator.estimate(inputs(sidecar = """{"content":"abcd"}"""))
        assertFalse(estimate.calibrated)
        assertNull(estimate.tools)
        val json = estimate.toAgentContextJson()
        assertFalse(json.getValue("calibrated").jsonPrimitive.boolean)
        assertFalse(json.getValue("tools_derived").jsonPrimitive.boolean)
    }

    @Test
    fun theWindowFollowsLettaCodesPrecedence() {
        val agent = obj("""{"model_settings":{"context_window_limit":200000},"llm_config":{"context_window":1000}}""")
        assertEquals(ContextWindowSize(200_000, "agent.model_settings.context_window_limit"), ContextBreakdownEstimator.windowOf(agent, null))
        val conversation = obj("""{"model_settings":{"context_window_limit":64000}}""")
        assertEquals(64_000, ContextBreakdownEstimator.windowOf(agent, conversation)?.tokens)
        assertEquals(32_000, ContextBreakdownEstimator.windowOf(agent, obj("""{"context_window_limit":32000}"""))?.tokens)
        assertEquals(1_000, ContextBreakdownEstimator.windowOf(obj("""{"llm_config":{"context_window":1000}}"""), null)?.tokens)
        assertNull(ContextBreakdownEstimator.windowOf(obj("""{}"""), null))
    }

    private fun inputs(
        sidecar: String? = null,
        lines: List<String> = emptyList(),
        reportedTotal: Int? = null,
    ) = LocalContextInputs(
        systemPromptSidecar = sidecar?.let(::obj),
        agent = obj("""{"id":"agent-1","system":"fallback"}"""),
        conversation = null,
        transcript = LocalTranscriptContext.read(lines.asSequence()),
        reportedTotal = reportedTotal,
    )
}
