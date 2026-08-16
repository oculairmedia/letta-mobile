package com.letta.mobile.data.context

import com.letta.mobile.data.model.ContextWindowOverview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextWindowUsageTest {
    private fun overview(
        max: Int = 200_000,
        current: Int = 0,
        system: Int = 0,
        tools: Int = 0,
        core: Int = 0,
        messages: Int = 0,
        files: Int = 0,
    ) = ContextWindowOverview(
        contextWindowSizeMax = max,
        contextWindowSizeCurrent = current,
        numTokensSystem = system,
        numTokensFunctionsDefinitions = tools,
        numTokensCoreMemory = core,
        numTokensMessages = messages,
        numTokensMemoryFilesystem = files,
    )

    @Test
    fun ordersOccupiedSegmentsLargestFirstAndDropsEmptyOnes() {
        val usage = ContextWindowUsage.from(
            overview(current = 24_000, system = 4_000, tools = 16_000, messages = 4_000),
        )

        assertEquals(
            listOf(
                ContextWindowSegmentKind.ToolDefinitions,
                ContextWindowSegmentKind.Messages,
                ContextWindowSegmentKind.System,
            ),
            usage.segments.map { it.kind },
        )
        assertTrue(usage.segments.none { it.tokens == 0 })
    }

    @Test
    fun bookesTheUnitemisedRemainderWhenTheTotalExceedsTheSections() {
        // The server reports more used than it itemises; the difference has to
        // appear as a segment, or the bar and the header disagree.
        val usage = ContextWindowUsage.from(overview(max = 100_000, current = 30_000, system = 1_000))

        assertEquals(30_000, usage.usedTokens)
        assertEquals(70_000, usage.freeTokens)
        assertEquals(0.7f, usage.freeSegment.fraction)
        val unitemised = usage.segments.single { it.kind == ContextWindowSegmentKind.Unitemised }
        assertEquals(29_000, unitemised.tokens)
        assertEquals(30_000, usage.segments.sumOf { it.tokens })
    }

    @Test
    fun letsTheSectionsWinWhenTheyExceedTheReportedTotal() {
        // A total that contradicts its own components cannot be shown next to
        // them, so the parts set the used figure.
        val usage = ContextWindowUsage.from(
            overview(max = 100_000, current = 5_000, system = 4_000, messages = 9_000),
        )

        assertEquals(13_000, usage.usedTokens)
        assertEquals(87_000, usage.freeTokens)
        assertTrue(usage.segments.none { it.kind == ContextWindowSegmentKind.Unitemised })
    }

    @Test
    fun fractionsAlwaysTotalTheWholeWindow() {
        val usage = ContextWindowUsage.from(
            overview(max = 128_000, current = 41_000, system = 4_300, tools = 16_300, messages = 15_200),
        )

        val total = usage.segments.sumOf { it.fraction.toDouble() } + usage.freeSegment.fraction
        assertTrue(kotlin.math.abs(total - 1.0) < 1e-6, "segments + free should cover the window, was $total")
    }

    @Test
    fun fallsBackToTheSegmentSumWhenTheServerOmitsTheTotal() {
        val usage = ContextWindowUsage.from(overview(max = 100_000, system = 2_000, messages = 3_000))

        assertEquals(5_000, usage.usedTokens)
        assertEquals(95_000, usage.freeTokens)
    }

    @Test
    fun reportsZeroFractionsWhenTheWindowSizeIsUnknown() {
        val usage = ContextWindowUsage.from(overview(max = 0, current = 5_000, system = 5_000))

        assertEquals(0f, usage.usedFraction)
        assertEquals(0, usage.freeTokens)
        assertEquals(0f, usage.segments.single().fraction)
    }

    @Test
    fun widensTheWindowWhenItsContentsOverflowTheStatedLimit() {
        // A window smaller than its own contents is not a window to draw
        // against, so the contents set the scale and free space is nil.
        val usage = ContextWindowUsage.from(overview(max = 10_000, current = 12_000, messages = 12_000))

        assertEquals(12_000, usage.usedTokens)
        assertEquals(12_000, usage.maxTokens)
        assertEquals(0, usage.freeTokens)
        assertEquals(1f, usage.usedFraction)
    }

    @Test
    fun formatsTokenCounts() {
        assertEquals("842", formatContextTokens(842))
        assertEquals("50.2k", formatContextTokens(50_237))
        assertEquals("16k", formatContextTokens(16_000))
        assertEquals("1M", formatContextTokens(1_000_000))
        assertEquals("1.5M", formatContextTokens(1_500_000))
    }

    @Test
    fun formatsSharesAndPercents() {
        assertEquals("1.6%", formatContextShare(0.0163f))
        assertEquals("95.0%", formatContextShare(0.95f))
        assertEquals("5%", formatContextPercent(0.0502f))
        assertEquals("100%", formatContextPercent(1f))
    }
}
