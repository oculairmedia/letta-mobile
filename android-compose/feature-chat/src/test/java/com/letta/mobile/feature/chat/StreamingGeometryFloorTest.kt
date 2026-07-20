package com.letta.mobile.feature.chat

import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreamingGeometryFloorTest {

    @Test
    fun `streaming geometry floor follows render bucket across content growth`() {
        val short = scrollTestGeometrySignature(content = "Hello")
        val longer = scrollTestGeometrySignature(content = "Hello, this is still streaming")
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(short, heightPx = 120, isStreaming = true),
                    ScrollTestGeometryMeasurement(longer, heightPx = 96, isStreaming = true),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(longer, isStreaming = true, expected = 120),
                ),
                followUpMeasurements = listOf(
                    ScrollTestGeometryMeasurement(longer, heightPx = 148, isStreaming = true),
                ),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(longer, isStreaming = true, expected = 148),
                ),
            ),
        )
    }

    @Test
    fun `streaming geometry floor does not constrain settled content`() {
        val short = scrollTestGeometrySignature(content = "Hello")
        val longer = scrollTestGeometrySignature(content = "Hello after the stream ends")
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(short, heightPx = 120, isStreaming = true),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(longer, isStreaming = false, expected = 0),
                ),
            ),
        )
    }

    @Test
    fun `clear streaming floors removes existing streaming bounds`() {
        val streamingItem = scrollTestGeometrySignature(content = "Streaming response...")
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(streamingItem, heightPx = 300, isStreaming = true),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(streamingItem, isStreaming = true, expected = 300),
                ),
                stateMutations = listOf(GeometryStateMutation.ClearStreamingFloors),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(streamingItem, isStreaming = true, expected = 0),
                ),
            ),
        )
    }

    @Test
    fun `streaming geometry measurement does not seed settled exact height for same content`() {
        val tableMessage = scrollTestGeometrySignature(
            content = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter table",
        )
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(tableMessage, heightPx = 420, isStreaming = true),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(tableMessage, isStreaming = true, expected = 420),
                    GeometryFloorAssertion(tableMessage, isStreaming = false, expected = 0),
                ),
                followUpMeasurements = listOf(
                    ScrollTestGeometryMeasurement(tableMessage, heightPx = 180, isStreaming = false),
                ),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(tableMessage, isStreaming = false, expected = 180),
                ),
            ),
        )
    }

    @Test
    fun `inactive streaming geometry buckets are pruned`() {
        val first = scrollTestGeometrySignature(renderKey = "msg-first", content = "first")
        val firstGrown = scrollTestGeometrySignature(renderKey = "msg-first", content = "first after more tokens")
        val second = scrollTestGeometrySignature(renderKey = "msg-second", content = "second")
        val secondGrown = scrollTestGeometrySignature(renderKey = "msg-second", content = "second after more tokens")
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(first, heightPx = 120, isStreaming = true),
                    ScrollTestGeometryMeasurement(second, heightPx = 80, isStreaming = true),
                ),
                stateMutations = listOf(GeometryStateMutation.RetainStreamingBuckets(setOf(second.bucket))),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(firstGrown, isStreaming = true, expected = 0),
                    GeometryFloorAssertion(secondGrown, isStreaming = true, expected = 80),
                ),
            ),
        )
    }

    @Test
    fun `render item geometry signature changes for width scale direction expansion and content`() {
        val item = scrollTestSingle("assistant", content = "hello")
        val base = item.scrollTestGeometrySignature()

        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(widthPx = 480)))
        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = 1.2f)))
        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(layoutDirection = LayoutDirection.Rtl)))
        assertNotEquals(base, scrollTestSingle("assistant", content = "hello world").scrollTestGeometrySignature())

        val reasoning = scrollTestSingle("reasoning", content = "thinking").copy(
            message = scrollTestMessage(
                ScrollTestMessageSpec(id = "reasoning", content = "thinking", role = ScrollTestMessageRole(isReasoning = true)),
            ),
        )
        val collapsed = reasoning.scrollTestGeometrySignature(ScrollTestGeometryOptions(state = ChatUiState()))
        val expanded = reasoning.scrollTestGeometrySignature(
            ScrollTestGeometryOptions(state = ChatUiState(expandedReasoningMessageIds = persistentSetOf("reasoning"))),
        )

        assertNotEquals(collapsed, expanded)
    }

    @Test
    fun `render item geometry signature samples long content changes without full text hash`() {
        val baseContent = "a".repeat(200)
        val changedContent = baseContent.replaceRange(100, 101, "b")

        val base = scrollTestSingle("assistant", content = baseContent).scrollTestGeometrySignature()
        val changed = scrollTestSingle("assistant", content = changedContent).scrollTestGeometrySignature()

        assertEquals(base.contentLength, changed.contentLength)
        assertNotEquals(base.contentHash, changed.contentHash)
    }

    @Test
    fun `geometry floor is invalidated when chatFontScaleBucket changes for streaming and settled content`() {
        assertAllFontScaleBucketInvalidations(
            FontScaleBucketInvalidationCase(
                content = "Streaming response...",
                isStreaming = true,
                heightPx = 300,
                baseScale = 1.0f,
                altScale = 0.8f,
            ),
            FontScaleBucketInvalidationCase(
                content = "Settled response.",
                isStreaming = false,
                heightPx = 150,
                baseScale = 1.0f,
                altScale = 1.5f,
            ),
        )
    }
}

private data class FontScaleBucketInvalidationCase(
    val content: String,
    val isStreaming: Boolean,
    val heightPx: Int,
    val baseScale: Float,
    val altScale: Float,
)

private fun assertAllFontScaleBucketInvalidations(vararg cases: FontScaleBucketInvalidationCase) {
    cases.forEach(::assertFontScaleBucketInvalidation)
}

private fun assertFontScaleBucketInvalidation(case: FontScaleBucketInvalidationCase) {
    val harness = ScrollTestGeometryHarness()
    val base = scrollTestSingle("assistant", content = case.content)
        .scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = case.baseScale))
    val alt = scrollTestSingle("assistant", content = case.content)
        .scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = case.altScale))
    harness.record(ScrollTestGeometryMeasurement(base, heightPx = case.heightPx, isStreaming = case.isStreaming))
    assertEquals(case.heightPx, harness.floor(base, isStreaming = case.isStreaming))
    assertEquals(0, harness.floor(alt, isStreaming = case.isStreaming))
}

private sealed interface GeometryStateMutation {
    data object ClearStreamingFloors : GeometryStateMutation
    data class RetainStreamingBuckets(val buckets: Set<com.letta.mobile.ui.chat.render.ChatMessageGeometryBucket>) : GeometryStateMutation
}

private data class StreamingGeometryScenario(
    val measurements: List<ScrollTestGeometryMeasurement> = emptyList(),
    val floorAssertions: List<GeometryFloorAssertion> = emptyList(),
    val stateMutations: List<GeometryStateMutation> = emptyList(),
    val followUpMeasurements: List<ScrollTestGeometryMeasurement> = emptyList(),
    val followUpFloorAssertions: List<GeometryFloorAssertion> = emptyList(),
)

private fun runStreamingGeometryScenario(scenario: StreamingGeometryScenario) {
    val harness = ScrollTestGeometryHarness()
    scenario.measurements.forEach(harness::record)
    scenario.floorAssertions.forEach { assertion -> assertion.verify(harness) }
    scenario.stateMutations.forEach { it.apply(harness.state) }
    scenario.followUpMeasurements.forEach(harness::record)
    scenario.followUpFloorAssertions.forEach { assertion -> assertion.verify(harness) }
}

private fun GeometryStateMutation.apply(state: ChatMessageGeometryState) {
    when (this) {
        GeometryStateMutation.ClearStreamingFloors -> state.clearStreamingFloors()
        is GeometryStateMutation.RetainStreamingBuckets -> state.retainStreamingBuckets(buckets)
    }
}
