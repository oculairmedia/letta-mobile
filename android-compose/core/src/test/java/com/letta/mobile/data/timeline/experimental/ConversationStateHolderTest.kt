package com.letta.mobile.data.timeline.experimental

import app.cash.turbine.test
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.Test

/**
 * letta-mobile-oc8j Phase 1 parity tests.
 *
 * Validates that [ConversationStateHolder] produces the same terminal
 * [Timeline] state as a hand-written fold over the same canonical fixtures
 * — proving the Molecule plumbing doesn't introduce side effects beyond
 * the pure [reduceStreamFrame] (which has its own per-branch unit
 * coverage in `TimelineStreamReducerTest` from letta-mobile-bfqgi).
 *
 * If the parity assertions hold and Phase 2 sees no observable latency
 * penalty, `TimelineSyncLoop` will migrate to consume the holder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ConversationStateHolderTest {

    private val conversationId = "conv-test"

    @Test
    fun `holder state matches direct reduceStreamFrame fold for streaming deltas`() = runTest(UnconfinedTestDispatcher()) {
        val fragments = listOf("Hello ", "world", "!")
        val expectedConcat = "Hello world!"

        val frames = fragments.mapIndexed { idx, body ->
            AssistantMessage(
                id = "reply-stream",
                contentRaw = JsonPrimitive(body),
                otid = if (idx == 0) "reply-otid-oc8j" else null,
            )
        }

        // Compute the direct-fold reference: drive reduceStreamFrame
        // manually over the same fixture, using empty pending-tool-returns.
        val direct = frames.fold(initialFold(conversationId)) { acc, frame ->
            val output = reduceStreamFrame(
                TimelineReducerInput(
                    prev = acc.timeline,
                    frame = frame,
                    pendingToolReturnsByCallId = acc.pending,
                )
            )
            Fold(output.next, output.updatedPendingToolReturnsByCallId)
        }
        val directTerminalContent = direct.timeline.events
            .filterIsInstance<com.letta.mobile.data.timeline.TimelineEvent.Confirmed>()
            .firstOrNull { it.serverId == "reply-stream" }
            ?.content

        directTerminalContent shouldBe expectedConcat

        // Drive the holder through the same fixture.
        val frameFlow = MutableSharedFlow<LettaMessage>(replay = 0)
        val holder = ConversationStateHolder(
            conversationId = conversationId,
            scope = backgroundScope,
            frames = frameFlow,
        )

        holder.state.test {
            // Seed emission.
            awaitItem().events.size shouldBe 0

            frames.forEach { frameFlow.emit(it) }

            // Walk emissions until the merged reply matches expectedConcat
            // — emissions may be conflated by the underlying StateFlow.
            var holderTerminal: String? = null
            while (holderTerminal != expectedConcat) {
                val tl = awaitItem()
                val match = tl.events
                    .filterIsInstance<com.letta.mobile.data.timeline.TimelineEvent.Confirmed>()
                    .firstOrNull { it.serverId == "reply-stream" }
                if (match != null) holderTerminal = match.content
            }

            holderTerminal shouldBe directTerminalContent
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * letta-mobile-bmgro (oc8j Phase 3a).
     *
     * Verifies that an emission on `hydrationSeed` rebases the fold: the
     * holder's terminal Timeline equals (seed-hydrated Timeline) folded
     * over the subsequent stream frames. Mirrors the loop's call path
     * where `hydrate()` writes its post-reduce Timeline into the seed
     * before any further stream frames arrive.
     */
    @Test
    fun `holder rebases fold on hydrationSeed emission so post-hydrate stream parity holds`() = runTest(UnconfinedTestDispatcher()) {
        // Pre-seed Timeline carrying one confirmed user message — analogue
        // of what TimelineHydrationReducer would have produced.
        val seedFrame = UserMessage(
            id = "user-hydrated-1",
            contentRaw = JsonPrimitive("hello from history"),
        )
        val seedDirect = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = conversationId),
                frame = seedFrame,
                pendingToolReturnsByCallId = emptyMap(),
            )
        ).next

        val streamFragments = listOf("Reply ", "after ", "hydrate")
        val streamFrames = streamFragments.mapIndexed { idx, body ->
            AssistantMessage(
                id = "reply-after-hydrate",
                contentRaw = JsonPrimitive(body),
                otid = if (idx == 0) "reply-otid-bmgro" else null,
            )
        }
        val expectedConcat = streamFragments.joinToString("")

        // Direct reference: fold the same fixture by hand starting from
        // the seed Timeline (no pending tool returns — matches the holder's
        // re-seed semantics).
        val directTerminal = streamFrames.fold(seedDirect) { acc, frame ->
            reduceStreamFrame(
                TimelineReducerInput(
                    prev = acc,
                    frame = frame,
                    pendingToolReturnsByCallId = emptyMap(),
                )
            ).next
        }

        val frameFlow = MutableSharedFlow<LettaMessage>(replay = 0)
        val hydrationSeed = MutableStateFlow(Timeline(conversationId = conversationId))
        val holder = ConversationStateHolder(
            conversationId = conversationId,
            scope = backgroundScope,
            frames = frameFlow,
            hydrationSeed = hydrationSeed,
        )

        holder.state.test {
            // Initial seed (empty Timeline) is the StateFlow's stateIn default;
            // followed by an emission once flatMapLatest spins up the inner
            // scan over the initial hydrationSeed.value (also empty here).
            awaitItem().events.size shouldBe 0

            // Rebase the fold by emitting the hydrated Timeline.
            hydrationSeed.value = seedDirect

            // Drive subsequent stream frames.
            streamFrames.forEach { frameFlow.emit(it) }

            // Walk emissions until terminal state matches the direct fold.
            var holderTerminal: Timeline = awaitItem()
            while (holderTerminal.events
                    .filterIsInstance<TimelineEvent.Confirmed>()
                    .firstOrNull { it.serverId == "reply-after-hydrate" }
                    ?.content != expectedConcat
            ) {
                holderTerminal = awaitItem()
            }

            // Holder must include BOTH the hydrated user message AND the
            // streamed assistant reply.
            val hydratedUserPresent = holderTerminal.events
                .filterIsInstance<TimelineEvent.Confirmed>()
                .any { it.serverId == "user-hydrated-1" }
            hydratedUserPresent shouldBe true

            // Terminal event sets equal between holder and direct fold.
            holderTerminal.events.size shouldBe directTerminal.events.size

            cancelAndIgnoreRemainingEvents()
        }
    }

    private data class Fold(
        val timeline: Timeline,
        val pending: Map<String, com.letta.mobile.data.model.ToolReturnMessage>,
    )

    private fun initialFold(conversationId: String) = Fold(
        timeline = Timeline(conversationId = conversationId),
        pending = emptyMap(),
    )
}
