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

    @Test
    fun denseTranscriptSeparatesMessagesFromFixedPromptOverhead() {
        val messages = denseHistory()
        val inputs = LocalContextInputs(
            obj("""{"content":"${"s".repeat(36_000)}","coreMemory":"${"s".repeat(32_000)}"}"""),
            obj("{}"), null, LocalTranscriptContext(null, messages, 0), 830_000,
        )
        val oldMessages = (2_160 + 1_650_240) / 4
        assertTrue((830_000 - 9_000 - oldMessages) / 830_000.0 > 0.30)
        val result = ContextBreakdownEstimator.estimate(inputs)
        assertTrue(result.sections.messages in 750_000..780_000, "Messages: ${result.sections.messages}")
        val wire = result.toAgentContextJson()
        assertTrue(wire.getValue("num_tokens_tools_and_harness").jsonPrimitive.int in 54_000..58_000)
        assertEquals(result.total, result.sections.total + (result.tools ?: 0))
        val harness = wire.getValue("num_tokens_tools_and_harness").jsonPrimitive.int
        assertTrue((result.tools!! - harness) < result.total / 10)
        // An old client ignores additive fields and still sums exactly to the provider total.
        val legacyFields = listOf(
            "num_tokens_system", "num_tokens_core_memory", "num_tokens_summary_memory",
            "num_tokens_messages", "num_tokens_functions_definitions",
        )
        assertEquals(result.total, legacyFields.sumOf { wire.getValue(it).jsonPrimitive.int })
        listOf(1, 5_000, 100_000, 500_000, 830_000, 900_000).forEach { total ->
            val scaled = ContextBreakdownEstimator.estimate(inputs.copy(reportedTotal = total))
            assertEquals(total, scaled.sections.total + scaled.tools!!)
            assertTrue(scaled.toolsAndHarness!! <= scaled.tools)
        }
    }

    @Test
    fun calibrationFallsBackWithoutEnoughCleanSameModelGrowth() {
        val point = obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":66000}}""")
        val text = obj("""{"role":"user","content":"${"x".repeat(40_000)}"}""")
        val later = obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":86000}}""")
        val backwards = obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":65000}}""")
        val switched = obj("""{"role":"assistant","model":"other-model","content":"","usage":{"input":86000}}""")
        val cases = listOf(
            LocalTranscriptContext(null, listOf(text), 0),
            LocalTranscriptContext(null, listOf(point, text), 0),
            LocalTranscriptContext(null, listOf(point, text, backwards), 0),
            LocalTranscriptContext(null, listOf(point, text, switched), 0),
            LocalTranscriptContext(obj("""{"content":"summary"}"""), listOf(point, text, later), 2),
        )
        cases.forEach { transcript ->
            val result = ContextBreakdownEstimator.estimate(LocalContextInputs(null, obj("{}"), null, transcript, 90_000))
            assertEquals(LocalMessageTokens.ofAll(transcript.messages), result.sections.messages)
            assertNull(result.toAgentContextJson()["calibration_source"])
            assertEquals(result.total, result.sections.total + (result.tools ?: 0))
        }
    }

    @Test
    fun oneUsagePointNamesOverheadWithoutClaimingMeasuredDensity() {
        val result = reviewFixture(firstPrompt = null, growthChars = 0)
        val wire = result.toAgentContextJson()
        assertEquals(540, result.sections.messages)
        assertTrue((result.toolsAndHarness ?: 0) > 50_000, "A single usage point must name fixed overhead")
        assertEquals("recorded_usage", wire["overhead_source"]?.jsonPrimitive?.content)
        assertNull(wire["calibration_source"])
        assertNull(wire["message_chars_per_token"])
        assertTrue(result.tools!! - result.toolsAndHarness!! < result.total / 10)
    }

    @Test
    fun aModelSwitchRestartsTheFitAtTheLatestRun() {
        val result = reviewFixture(firstPrompt = 64_000, firstModel = "previous-model")
        assertEquals(21_000, result.sections.messages)
        assertEquals(65_000, result.toolsAndHarness)
    }

    @Test
    fun aNonIncreasingPromptRestartsRatherThanPoisoningLaterGrowth() {
        val result = reviewFixture(firstPrompt = 67_000)
        assertEquals(21_000, result.sections.messages)
        assertEquals(65_000, result.toolsAndHarness)
    }

    @Test
    fun aSwitchWithoutEnoughSubsequentGrowthOnlyNamesOverhead() {
        val result = reviewFixture(firstPrompt = 64_000, firstModel = "previous-model", growthChars = 432)
        assertEquals(648, result.sections.messages)
        assertNull(result.messageCharsPerToken)
        assertTrue((result.toolsAndHarness ?: 0) > 0)
    }

    @Test
    fun aShortNewModelRunReusesEarlierDensityButLeavesItsOverheadUnnamed() {
        for (turns in listOf(1, 2)) {
            val messages = denseHistory().toMutableList()
            repeat(turns) { turn ->
                messages += obj("""{"role":"user","content":"${"x".repeat(216)}"}""")
                messages += obj(
                    """{"role":"assistant","model":"new-model","content":"","usage":{"input":${830_100 + turn * 100}}}""",
                )
            }
            val result = denseEstimate(messages)
            assertEquals(2.16, result.messageCharsPerToken)
            assertEquals(765_000 + turns * 100, result.sections.messages)
            assertNull(result.toolsAndHarness)
            assertEquals(56_000, result.tools)
            assertNull(result.toAgentContextJson()["overhead_source"])
            assertEquals(true, result.toAgentContextJson()["calibration_approximate"]?.jsonPrimitive?.boolean)
            assertEquals(result.total, result.sections.total + result.tools!!)
        }
    }

    @Test
    fun aNonIncreasingLatePointReusesTheEarlierFit() {
        val messages = denseHistory() + obj(
            """{"role":"assistant","model":"fixture","content":"","usage":{"input":829000}}""",
        )
        val result = denseEstimate(messages)
        assertEquals(2.16, result.messageCharsPerToken)
        assertTrue((result.toolsAndHarness ?: 0) in 54_000..58_000)
        assertEquals(true, result.toAgentContextJson()["calibration_approximate"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun aSameModelPromptBelowTheEarlierOverheadKeepsDensityButNamesNoOverhead() {
        // The earlier run fits about 56k of fixed overhead; the same model then reports a prompt of 6,000.
        val messages = denseHistory() + obj(
            """{"role":"assistant","model":"fixture","content":"","usage":{"input":6000}}""",
        )
        val result = denseEstimate(messages)
        assertEquals(2.16, result.messageCharsPerToken, "the density of the text is still reusable")
        assertNull(result.toolsAndHarness, "a 6,000-token prompt cannot contain 56,000 tokens of overhead")
        assertNull(result.toAgentContextJson()["overhead_source"])
        assertEquals(true, result.toAgentContextJson()["calibration_approximate"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun aSingleLateUsagePointCannotNameMessageErrorAsOverhead() {
        val result = denseEstimate(listOf(
            obj("""{"role":"user","content":"${"x".repeat(1_648_017)}"}"""),
            obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":829747}}"""),
        ))
        assertNull(result.toolsAndHarness, "Late usage alone cannot identify fixed overhead")
        assertNull(result.messageCharsPerToken)
        assertNull(result.toAgentContextJson()["overhead_source"])
    }

    @Test
    fun boundedSinglePointOverheadDoesNotLookOutsideTheCurrentRun() {
        val messages = denseHistory().dropLast(1) + obj(
            """{"role":"assistant","model":"new-model","content":"","usage":{"input":830000}}""",
        )
        val result = denseEstimate(messages)
        assertNull(result.messageCharsPerToken)
        assertNull(result.toolsAndHarness)
    }

    @Test
    fun anAdequateLatestRunTakesPriorityOverTheEarlierFit() {
        val messages = historyWithTwoSufficientRuns()
        val result = denseEstimate(messages)
        assertEquals(4.0, result.messageCharsPerToken)
        assertTrue(result.toAgentContextJson()["calibration_approximate"]?.jsonPrimitive?.boolean != true)
        assertTrue((result.toolsAndHarness ?: 0) in 54_000..58_000)
    }

    @Test
    fun theMostRecentSufficientEarlierRunWinsOverOlderFits() {
        val messages = historyWithTwoSufficientRuns() + listOf(
            obj("""{"role":"user","content":"${"x".repeat(216)}"}"""),
            obj("""{"role":"assistant","model":"third-model","content":"","usage":{"input":488208}}"""),
        )
        val result = denseEstimate(messages)
        assertEquals(4.0, result.messageCharsPerToken)
        assertEquals(423_208, result.sections.messages)
        assertNull(result.toolsAndHarness)
        assertEquals(56_000, result.tools)
        assertEquals(true, result.toAgentContextJson()["calibration_approximate"]?.jsonPrimitive?.boolean)
    }

    private fun historyWithTwoSufficientRuns(): List<JsonObject> = denseHistory() + listOf(
        obj("""{"role":"user","content":"${"x".repeat(216)}"}"""),
        obj("""{"role":"assistant","model":"new-model","content":"","usage":{"input":478154}}"""),
        obj("""{"role":"user","content":"${"x".repeat(40_000)}"}"""),
        obj("""{"role":"assistant","model":"new-model","content":"","usage":{"input":488154}}"""),
    )

    @Test
    fun overheadCannotComeFromAnEarlierModelPoint() {
        val points = twoModelPoints()
        val messages = listOf(points.first(), obj("""{"role":"user","content":"${"x".repeat(20_000)}"}"""), points.last())
        val inputs = LocalContextInputs(null, obj("{}"), null, LocalTranscriptContext(null, messages, 0), null)
        val result = ContextBreakdownEstimator.estimate(inputs)
        assertNull(result.toolsAndHarness, "Old-model overhead must not be normalized into a new-model answer")
    }

    @Test
    fun anOldModelPromptCannotTurnTheKnownCurrentSystemIntoPhantomTools() {
        val sidecar = obj("""{"content":"${"s".repeat(20_000)}"}""")
        val transcript = LocalTranscriptContext(null, twoModelPoints(), 0)
        val result = ContextBreakdownEstimator.estimate(LocalContextInputs(sidecar, obj("{}"), null, transcript, null))
        assertEquals(5_000, result.sections.system)
        assertEquals(0, result.toolsAndHarness)
        assertEquals(5_000, result.total)
    }

    private fun twoModelPoints(): List<JsonObject> = listOf(
        obj("""{"role":"assistant","model":"old-model","content":"","usage":{"input":50000}}"""),
        obj("""{"role":"assistant","model":"new-model","content":"","usage":{"input":5000}}"""),
    )

    @Test
    fun anEarlierModelFitSuppliesDensityButNotItsOverhead() {
        val result = denseEstimate(denseHistory() + obj(
            """{"role":"assistant","model":"new-model","content":"","usage":{"input":830000}}""",
        ))
        assertEquals(2.16, result.messageCharsPerToken)
        assertNull(result.toolsAndHarness)
        assertEquals(true, result.calibrationApproximate)
    }

    @Test
    fun scalingAFittedBreakdownMarksItsProvenanceApproximate() {
        val input = LocalContextInputs(null, obj("{}"), null, LocalTranscriptContext(null, denseHistory(), 0), 400_000)
        val result = ContextBreakdownEstimator.estimate(input)
        assertTrue(result.scaledDown)
        assertTrue(result.calibrationApproximate)
        assertEquals(true, result.toAgentContextJson()["calibration_approximate"]?.jsonPrimitive?.boolean)
    }

    private fun denseHistory(): List<JsonObject> = listOf(
        obj("""{"role":"user","content":"${"x".repeat(2_160)}"}"""),
        obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":66000}}"""),
        obj("""{"role":"user","content":"${"x".repeat(1_650_240)}"}"""),
        obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":830000}}"""),
    )

    private fun denseEstimate(messages: List<JsonObject>): ContextBreakdownEstimate = ContextBreakdownEstimator.estimate(
        LocalContextInputs(
            obj("""{"content":"${"s".repeat(36_000)}","coreMemory":"${"s".repeat(32_000)}"}"""),
            obj("{}"), null, LocalTranscriptContext(null, messages, 0), null,
        ),
    )

    private fun reviewFixture(
        firstPrompt: Int?,
        firstModel: String = "fixture",
        growthChars: Int = 43_200,
    ): ContextBreakdownEstimate {
        val lastPrompt = if (growthChars == 43_200) 86_000 else 66_000 + growthChars / 2
        val messages = buildList {
            firstPrompt?.let {
                add(obj("""{"role":"assistant","model":"$firstModel","content":"","usage":{"input":$it}}"""))
            }
            add(obj("""{"role":"user","content":"${"x".repeat(2_160)}"}"""))
            add(obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":66000}}"""))
            if (growthChars > 0) {
                add(obj("""{"role":"user","content":"${"x".repeat(growthChars)}"}"""))
                add(obj("""{"role":"assistant","model":"fixture","content":"","usage":{"input":$lastPrompt}}"""))
            }
        }
        // 43,200 chars / 20,000 tokens = 2.16 chars/token, with a 65k fixed intercept.
        val transcript = LocalTranscriptContext(null, messages, 0)
        return ContextBreakdownEstimator.estimate(LocalContextInputs(null, obj("{}"), null, transcript, null))
    }

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
        assertEquals(24L, LocalMessageTokens.chars(assistant))
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

    @Test
    fun clientAndRecordedTotalsCalibrateWithTheClientTakingPrecedence() {
        fun estimate(reportedTotal: Int?) = ContextBreakdownEstimator.estimate(
            inputs(
                sidecar = """{"content":"${"s".repeat(400)}"}""",
                lines = listOf("""{"id":"a","role":"assistant","content":[],"usage":{"totalTokens":5000}}"""),
                reportedTotal = reportedTotal,
            ),
        )

        val recorded = estimate(reportedTotal = null)
        assertEquals(ContextTotalSource.Recorded, recorded.totalSource)
        assertEquals(5_000, recorded.total)
        assertEquals(4_900, recorded.tools)

        val client = estimate(reportedTotal = 8_000)
        assertEquals(ContextTotalSource.Client, client.totalSource)
        assertEquals(8_000, client.total)
    }

    @Test
    fun aCompactionBoundaryRejectsPreviouslyRecordedUsageAndItsCacheParts() {
        val transcript = LocalTranscriptContext.read(
            sequenceOf(
                """{"type":"message","id":"e1","message":{"id":"m1","role":"assistant","content":[],
                    "usage":{"input":100,"output":20,"cacheRead":30000},"timestamp":10}}""",
                """{"type":"compaction","id":"e2","firstKeptEntryId":"e1","message":{"id":"m2","role":"user",
                    "content":"summary","timestamp":20,"metadata":{"compaction":{"summary":"s"}}}}""",
                """{"type":"message","id":"e3","message":{"id":"m3","role":"user","content":"12345678","timestamp":30}}""",
            ),
        )

        assertNull(transcript.recordedTotal())
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
