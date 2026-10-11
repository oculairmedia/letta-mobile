package com.letta.mobile.data.context

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** letta-mobile-cyh28: the meter's provenance and the lazy breakdown loader. */
class ContextMeterTest {
    private val estimate = ContextWindowOverview(
        contextWindowSizeMax = 200_000,
        contextWindowSizeCurrent = 40_000,
        numTokensSystem = 4_000,
        numTokensCoreMemory = 2_000,
        numTokensSummaryMemory = 1_000,
        numTokensMessages = 30_000,
        numTokensFunctionsDefinitions = 3_000,
        source = "estimate",
        calibrated = true,
        toolsDerived = true,
    )

    @Test
    fun aStreamedTotalAloneIsTotalOnly() {
        val meter = ContextMeter.of(streamedTotal = 84_000, windowTokens = 200_000)!!
        assertEquals(ContextProvenance.TotalOnly, meter.provenance)
        assertEquals(84_000, meter.usage.usedTokens)
        assertEquals(ContextWindowUsage.IN_CONTEXT_LABEL, meter.usage.segments.single().label)
    }

    @Test
    fun nothingAtAllIsNoMeter() {
        assertNull(ContextMeter.of(streamedTotal = null, windowTokens = 200_000))
    }

    @Test
    fun anOldHostsPlaceholderBreakdownIsIgnored() {
        val legacy = estimate.copy(source = null)
        val meter = ContextMeter.of(streamedTotal = 84_000, windowTokens = 200_000, breakdown = legacy)!!
        assertEquals(ContextProvenance.TotalOnly, meter.provenance)
    }

    @Test
    fun theBreakdownIsRematchedToTheNewestStreamedTotal() {
        val meter = ContextMeter.of(streamedTotal = 50_000, windowTokens = 128_000, breakdown = estimate)!!
        assertEquals(ContextProvenance.EstimatedCalibrated, meter.provenance)
        assertEquals(50_000, meter.usage.usedTokens)
        assertEquals(50_000, meter.usage.segments.sumOf { it.tokens })
        val tools = meter.usage.segments.single { it.kind == ContextWindowSegmentKind.ToolDefinitions }
        assertEquals("Tools & other", tools.label)
        assertEquals(50_000 - 37_000, tools.tokens)
        // The host's record window wins over the catalog's.
        assertEquals(200_000, meter.usage.maxTokens)
    }

    @Test
    fun afterACompactionTheBreakdownIsOnlyEstimated() {
        val uncalibrated = estimate.copy(calibrated = false, toolsDerived = false, numTokensFunctionsDefinitions = 0, contextWindowSizeCurrent = 37_000)
        val meter = ContextMeter.of(streamedTotal = 30_000, windowTokens = 200_000, breakdown = uncalibrated, totalIsEstimate = true)!!
        assertEquals(ContextProvenance.Estimated, meter.provenance)
        assertEquals(37_000, meter.usage.usedTokens)
    }

    @Test
    fun autoCompactionFiresAtLettaCodesThreshold() {
        assertEquals((200_000 - 16_384) / 200_000f, ContextMeter.autoCompactAt(200_000))
        assertEquals(0.8f, ContextMeter.autoCompactAt(10_000))
        assertNull(ContextMeter.autoCompactAt(null))
    }

    // ── loader ──────────────────────────────────────────────────────────────

    private val request = ContextBreakdownRequest(AgentId("agent-1"), ConversationId("conv-1"), reportedTotal = 40_000)

    @Test
    fun aRepeatRequestIsServedFromTheLastAnswer() = runTest {
        var calls = 0
        val loader = ContextBreakdownLoader { calls++; estimate }
        val first = loader.load(request)
        assertIs<ContextBreakdownState.Loaded>(first)
        assertSame(first, loader.load(request))
        assertEquals(1, calls)
        loader.load(request.copy(reportedTotal = 41_000))
        assertEquals(2, calls)
        assertEquals(estimate, loader.state.value.overviewFor(request))
    }

    @Test
    fun aHostWithoutTheStoreIsNotSupportedAndOtherFailuresAreFailures() = runTest {
        val noStore = ContextBreakdownLoader { error("capability_unavailable: 'agent.context' has no injected 'local_backend_store' service") }
        assertEquals(ContextBreakdownUnavailable.NotSupported, assertIs<ContextBreakdownState.Unavailable>(noStore.load(request)).reason)
        val oldHost = ContextBreakdownLoader { error("Unknown method: agent.context") }
        assertEquals(ContextBreakdownUnavailable.NotSupported, assertIs<ContextBreakdownState.Unavailable>(oldHost.load(request)).reason)
        val broken = ContextBreakdownLoader { error("socket closed") }
        assertEquals(ContextBreakdownUnavailable.Failed, assertIs<ContextBreakdownState.Unavailable>(broken.load(request)).reason)
    }

    @Test
    fun cancellationIsNeverSwallowed() = runTest {
        val loader = ContextBreakdownLoader { throw CancellationException("gone") }
        assertFailsWith<CancellationException> { loader.load(request) }
    }
}
