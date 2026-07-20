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
        val short = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "Hello"))
        val longer = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Hello, this is still streaming"),
        )
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(short, heightPx = 120, streaming = ScrollTestStreamingState.Streaming),
                    ScrollTestGeometryMeasurement(longer, heightPx = 96, streaming = ScrollTestStreamingState.Streaming),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(longer, streaming = ScrollTestStreamingState.Streaming, expected = 120),
                ),
                followUpMeasurements = listOf(
                    ScrollTestGeometryMeasurement(longer, heightPx = 148, streaming = ScrollTestStreamingState.Streaming),
                ),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(longer, streaming = ScrollTestStreamingState.Streaming, expected = 148),
                ),
            ),
        )
    }

    @Test
    fun `streaming geometry floor does not constrain settled content`() {
        val short = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "Hello"))
        val longer = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Hello after the stream ends"),
        )
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(short, heightPx = 120, streaming = ScrollTestStreamingState.Streaming),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(longer, streaming = ScrollTestStreamingState.Settled, expected = 0),
                ),
            ),
        )
    }

    @Test
    fun `clear streaming floors removes existing streaming bounds`() {
        val streamingItem = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Streaming response..."),
        )
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(streamingItem, heightPx = 300, streaming = ScrollTestStreamingState.Streaming),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(streamingItem, streaming = ScrollTestStreamingState.Streaming, expected = 300),
                ),
                stateMutations = listOf(GeometryStateMutation.ClearStreamingFloors),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(streamingItem, streaming = ScrollTestStreamingState.Streaming, expected = 0),
                ),
            ),
        )
    }

    @Test
    fun `streaming geometry measurement does not seed settled exact height for same content`() {
        val tableMessage = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter table"),
        )
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(tableMessage, heightPx = 420, streaming = ScrollTestStreamingState.Streaming),
                ),
                floorAssertions = listOf(
                    GeometryFloorAssertion(tableMessage, streaming = ScrollTestStreamingState.Streaming, expected = 420),
                    GeometryFloorAssertion(tableMessage, streaming = ScrollTestStreamingState.Settled, expected = 0),
                ),
                followUpMeasurements = listOf(
                    ScrollTestGeometryMeasurement(tableMessage, heightPx = 180, streaming = ScrollTestStreamingState.Settled),
                ),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(tableMessage, streaming = ScrollTestStreamingState.Settled, expected = 180),
                ),
            ),
        )
    }

    @Test
    fun `inactive streaming geometry buckets are pruned`() {
        val first = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(renderKey = "msg-first", content = "first"),
        )
        val firstGrown = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(renderKey = "msg-first", content = "first after more tokens"),
        )
        val second = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(renderKey = "msg-second", content = "second"),
        )
        val secondGrown = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(renderKey = "msg-second", content = "second after more tokens"),
        )
        runStreamingGeometryScenario(
            StreamingGeometryScenario(
                measurements = listOf(
                    ScrollTestGeometryMeasurement(first, heightPx = 120, streaming = ScrollTestStreamingState.Streaming),
                    ScrollTestGeometryMeasurement(second, heightPx = 80, streaming = ScrollTestStreamingState.Streaming),
                ),
                stateMutations = listOf(GeometryStateMutation.RetainStreamingBuckets(setOf(second.bucket))),
                followUpFloorAssertions = listOf(
                    GeometryFloorAssertion(firstGrown, streaming = ScrollTestStreamingState.Streaming, expected = 0),
                    GeometryFloorAssertion(secondGrown, streaming = ScrollTestStreamingState.Streaming, expected = 80),
                ),
            ),
        )
    }

    @Test
    fun `render item geometry signature changes for width scale direction expansion and content`() {
        val item = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = "hello"))
        val base = item.scrollTestGeometrySignature()

        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(widthPx = 480)))
        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = 1.2f)))
        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(layoutDirection = LayoutDirection.Rtl)))
        assertNotEquals(
            base,
            scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = "hello world"))
                .scrollTestGeometrySignature(),
        )

        val reasoning = scrollTestSingle(ScrollTestMessageSpec(id = "reasoning", content = "thinking")).copy(
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

        val base = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = baseContent))
            .scrollTestGeometrySignature()
        val changed = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = changedContent))
            .scrollTestGeometrySignature()

        assertEquals(base.contentLength, changed.contentLength)
        assertNotEquals(base.contentHash, changed.contentHash)
    }

    @Test
    fun `geometry floor is invalidated when chatFontScaleBucket changes for streaming and settled content`() {
        assertAllFontScaleBucketInvalidations(
            FontScaleBucketInvalidationCase(
                content = "Streaming response...",
                streaming = ScrollTestStreamingState.Streaming,
                heightPx = 300,
                scales = FontScaleBucketScales(base = 1.0f, alt = 0.8f),
            ),
            FontScaleBucketInvalidationCase(
                content = "Settled response.",
                streaming = ScrollTestStreamingState.Settled,
                heightPx = 150,
                scales = FontScaleBucketScales(base = 1.0f, alt = 1.5f),
            ),
        )
    }
}

private data class FontScaleBucketScales(
    val base: Float,
    val alt: Float,
)

private data class FontScaleBucketInvalidationCase(
    val content: String,
    val streaming: ScrollTestStreamingState,
    val heightPx: Int,
    val scales: FontScaleBucketScales,
)

private fun assertAllFontScaleBucketInvalidations(vararg cases: FontScaleBucketInvalidationCase) {
    cases.forEach(::assertFontScaleBucketInvalidation)
}

private fun assertFontScaleBucketInvalidation(case: FontScaleBucketInvalidationCase) {
    val harness = ScrollTestGeometryHarness()
    val base = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = case.content))
        .scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = case.scales.base))
    val alt = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = case.content))
        .scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = case.scales.alt))
    harness.record(ScrollTestGeometryMeasurement(base, heightPx = case.heightPx, streaming = case.streaming))
    assertEquals(case.heightPx, harness.floor(base, case.streaming))
    assertEquals(0, harness.floor(alt, case.streaming))
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
