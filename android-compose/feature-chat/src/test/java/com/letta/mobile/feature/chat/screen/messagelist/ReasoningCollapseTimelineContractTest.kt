package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.feature.chat.ScrollTestGeometryOptions
import com.letta.mobile.feature.chat.ScrollTestMessageRole
import com.letta.mobile.feature.chat.ScrollTestMessageSpec
import com.letta.mobile.feature.chat.scrollTestGeometrySignature
import com.letta.mobile.feature.chat.scrollTestMessage
import com.letta.mobile.feature.chat.scrollTestSingle
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaSpacingTokens
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * letta-mobile-fix-thinking-block-collapse regression contracts.
 *
 * Two coupled timeline defects around reasoning ("Thought") rows:
 *
 * 1. Collapsing an expanded reasoning block left the lazy item at its
 *    expanded height: [MeasuredChatRenderItem] re-read the geometry cache
 *    reactively, so the first post-collapse (mid-`animateContentSize`)
 *    measurement landed as `heightIn(min=…)` and pinned the Box above every
 *    later animated frame.
 * 2. Consecutive collapsed reasoning rows carried oversized vertical gaps
 *    because they were routed through the ungrouped section break plus a
 *    reasoning-specific bottom inset on top of MessageReasoning's own
 *    vertical chrome — unlike tool rows, which bypass the item padding.
 *
 * These tests fail closed: reverting either half of the fix breaks the build.
 */
class ReasoningCollapseTimelineContractTest {

    private val reasoningSpec = ScrollTestMessageSpec(
        id = "thought-1",
        content = "Let me break this down step by step.",
        role = ScrollTestMessageRole(isReasoning = true),
    )

    // region Geometry key / cache policy (symptom 1: collapse never shrinks)

    @Test
    fun `expanded to collapsed reasoning flips the geometry expansion fingerprint`() {
        val item = scrollTestSingle(reasoningSpec)

        val collapsed = item.scrollTestGeometrySignature(
            ScrollTestGeometryOptions(state = ChatUiState()),
        )
        val expanded = item.scrollTestGeometrySignature(
            ScrollTestGeometryOptions(
                state = ChatUiState(expandedReasoningMessageIds = persistentSetOf(reasoningSpec.id)),
            ),
        )

        assertNotEquals(
            "expansionHash must participate in the geometry bucket",
            expanded.bucket.expansionHash,
            collapsed.bucket.expansionHash,
        )
        assertNotEquals("expanded and collapsed rows must not share a cache entry", expanded, collapsed)
    }

    @Test
    fun `run block reasoning participation also flips the geometry fingerprint`() {
        val runMessages = listOf(
            scrollTestMessage(
                ScrollTestMessageSpec(id = "step-tool", content = ""),
            ) to GroupPosition.First,
            scrollTestMessage(reasoningSpec) to GroupPosition.Last,
        )
        // Sanity: the reasoning message really is inside this block.
        assertTrue(runMessages.any { it.first.isReasoning })

        fun signature(state: ChatUiState) = ChatRenderItem.RunBlock(
            runId = "run-1",
            messages = runMessages,
        ).scrollTestGeometrySignature(ScrollTestGeometryOptions(state = state))

        val allCollapsed = signature(ChatUiState())
        val thoughtExpanded = signature(
            ChatUiState(expandedReasoningMessageIds = persistentSetOf(reasoningSpec.id)),
        )

        assertNotEquals(allCollapsed, thoughtExpanded)
        assertNotEquals(
            allCollapsed.bucket.expansionHash,
            thoughtExpanded.bucket.expansionHash,
        )
    }

    @Test
    fun `height recorded while expanded is never served under the collapsed signature`() {
        val item = scrollTestSingle(reasoningSpec)
        val collapsed = item.scrollTestGeometrySignature(ScrollTestGeometryOptions(state = ChatUiState()))
        val expanded = item.scrollTestGeometrySignature(
            ScrollTestGeometryOptions(
                state = ChatUiState(expandedReasoningMessageIds = persistentSetOf(reasoningSpec.id)),
            ),
        )

        val state = ChatMessageGeometryState()
        state.recordMeasuredHeight(signature = expanded, heightPx = 600)

        assertNull(
            "a freshly collapsed reasoning row must cache-miss (no heightIn(min=…) floor " +
                "seeded from the expanded measurement)",
            state.heightFor(collapsed),
        )
    }

    @Test
    fun `collapse settles over animation intermediates without inheriting the expanded floor`() {
        val item = scrollTestSingle(reasoningSpec)
        val collapsed = item.scrollTestGeometrySignature(ScrollTestGeometryOptions(state = ChatUiState()))
        val expanded = item.scrollTestGeometrySignature(
            ScrollTestGeometryOptions(
                state = ChatUiState(expandedReasoningMessageIds = persistentSetOf(reasoningSpec.id)),
            ),
        )

        val state = ChatMessageGeometryState()
        state.recordMeasuredHeight(signature = expanded, heightPx = 600)
        // animateContentSize emits shrinking intermediates under the collapsed
        // signature before settling at the compact height.
        state.recordMeasuredHeight(signature = collapsed, heightPx = 585)
        state.recordMeasuredHeight(signature = collapsed, heightPx = 300)
        state.recordMeasuredHeight(signature = collapsed, heightPx = 96)

        assertEquals(96, state.heightFor(collapsed))
        assertNotEquals(
            "the collapsed key must never resolve to the expanded row's height",
            600,
            state.heightFor(collapsed),
        )
    }

    // endregion

    // region Min-height floor bypass scope (production wiring helper)

    @Test
    fun `single reasoning render items are excluded from the cached min-height floor`() {
        assertTrue(scrollTestSingle(reasoningSpec).includesReasoningRow())
        assertFalse(
            "non-reasoning singles must keep the historical cached-floor behaviour",
            scrollTestSingle(
                ScrollTestMessageSpec(id = "assistant-1", content = "answer"),
            ).includesReasoningRow(),
        )
    }

    @Test
    fun `run blocks containing any reasoning message are excluded from the floor`() {
        val withReasoning = ChatRenderItem.RunBlock(
            runId = "run-1",
            messages = listOf(
                scrollTestMessage(
                    ScrollTestMessageSpec(id = "step-tool", content = ""),
                ) to GroupPosition.First,
                scrollTestMessage(reasoningSpec.copy(id = "thought-2")) to GroupPosition.Last,
            ),
        )
        val withoutReasoning = ChatRenderItem.RunBlock(
            runId = "run-2",
            messages = listOf(
                scrollTestMessage(
                    ScrollTestMessageSpec(id = "step-a", content = "a"),
                ) to GroupPosition.First,
                scrollTestMessage(
                    ScrollTestMessageSpec(id = "step-b", content = "b"),
                ) to GroupPosition.Last,
            ),
        )

        assertTrue(withReasoning.includesReasoningRow())
        assertFalse(withoutReasoning.includesReasoningRow())
    }

    // endregion

    // region Vertical rhythm policy (symptom 2: oversized gaps between thoughts)

    private val grouped = LettaSpacingTokens.MESSAGE_SPACING
    private val ungrouped = LettaSpacingTokens.UNGROUPED_MESSAGE_SPACING

    @Test
    fun `reasoning rows use the compact grouped beat at every group position`() {
        listOf(GroupPosition.First, GroupPosition.Middle, GroupPosition.Last, GroupPosition.None)
            .forEach { position ->
                val padding = chatMessageRowVerticalPadding(
                    isReasoning = true,
                    position = position,
                    groupedMessageSpacingDp = grouped,
                    ungroupedMessageSpacingDp = ungrouped,
                )
                assertEquals(
                    "collapsed reasoning row at $position must not take the ungrouped section break",
                    grouped,
                    padding.topDp,
                    0f,
                )
                assertEquals(
                    "reasoning row at $position must carry NO item-level bottom inset " +
                        "(MessageReasoning already self-pads)",
                    0f,
                    padding.bottomDp,
                    0f,
                )
            }
    }

    @Test
    fun `non-reasoning rows keep the editorial position-based separation`() {
        listOf(GroupPosition.First, GroupPosition.None).forEach { position ->
            assertEquals(
                "role-transition row at $position must keep the section break",
                ungrouped,
                chatMessageRowVerticalPadding(
                    isReasoning = false,
                    position = position,
                    groupedMessageSpacingDp = grouped,
                    ungroupedMessageSpacingDp = ungrouped,
                ).topDp,
                0f,
            )
        }
        listOf(GroupPosition.Middle, GroupPosition.Last).forEach { position ->
            assertEquals(
                grouped,
                chatMessageRowVerticalPadding(
                    isReasoning = false,
                    position = position,
                    groupedMessageSpacingDp = grouped,
                    ungroupedMessageSpacingDp = ungrouped,
                ).topDp,
                0f,
            )
        }
    }

    // endregion

    // region Source contracts (fail closed if the wiring is reverted)

    @Test
    fun `MeasuredChatRenderItem gates the cached min-height floor behind applyCachedMinHeight`() {
        val source = itemsSource()
        assertTrue(
            "MeasuredChatRenderItem must declare the applyCachedMinHeight parameter",
            source.contains("applyCachedMinHeight: Boolean = true"),
        )
        val region = braceMatchedRegion(source, "internal fun MeasuredChatRenderItem(")
        assertTrue(
            "the heightFor cache read (and therefore the heightIn(min=…) floor) must be " +
                "gated on applyCachedMinHeight so reasoning rows never get a floor",
            region.contains("if (applyCachedMinHeight && hasMeasuredOnce.value)"),
        )
    }

    @Test
    fun `lazy column call site disables the floor for reasoning render items`() {
        val source = lazyColumnSource()
        assertTrue(
            "ChatMessageListRenderItem must pass applyCachedMinHeight = " +
                "!renderItem.includesReasoningRow() to MeasuredChatRenderItem",
            source.contains("applyCachedMinHeight = !renderItem.includesReasoningRow()"),
        )
    }

    @Test
    fun `RenderChatMessage routes row spacing through the reasoning-aware padding policy`() {
        val source = itemsSource()
        assertTrue(
            "RenderChatMessage must derive its vertical item padding from " +
                "chatMessageRowVerticalPadding (the policy under test)",
            source.contains("chatMessageRowVerticalPadding("),
        )
        assertFalse(
            "the reasoning-specific bottom inset (double-padding on top of " +
                "MessageReasoning's own chrome) must not return",
            source.contains("val spacingAbove = if (message.isReasoning)"),
        )
    }

    // endregion

    // region Source plumbing (mirrors ChatTimelineObserverCollapseContractTest)

    private fun itemsSource(): String = sourceFromCandidates(
        "src/main/java/com/letta/mobile/feature/chat/screen/messagelist/ChatMessageListItems.kt",
        "feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/messagelist/ChatMessageListItems.kt",
    )

    private fun lazyColumnSource(): String = sourceFromCandidates(
        "src/main/java/com/letta/mobile/feature/chat/screen/messagelist/ChatMessageListLazyColumn.kt",
        "feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/messagelist/ChatMessageListLazyColumn.kt",
    )

    private fun sourceFromCandidates(vararg relativePaths: String): String {
        val userDir = Path.of(System.getProperty("user.dir"))
        val path = relativePaths
            .map { userDir.resolve(it) }
            .firstOrNull { it.exists() }
            ?: error("source not found from user.dir=$userDir: ${relativePaths.joinToString()}")
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    /** Returns the balanced-brace region starting at the `{` following [marker]. */
    private fun braceMatchedRegion(text: String, marker: String): String {
        val markerIndex = text.indexOf(marker)
        assertTrue("marker not found: $marker", markerIndex >= 0)
        var depth = 0
        var started = false
        var i = text.indexOf('{', markerIndex)
        assertTrue("no brace after marker: $marker", i >= 0)
        val start = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> {
                    depth++
                    started = true
                }
                '}' -> {
                    depth--
                    if (started && depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after marker: $marker")
    }

    // endregion
}
