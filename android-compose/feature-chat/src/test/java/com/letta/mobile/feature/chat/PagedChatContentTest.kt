package com.letta.mobile.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.*
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PagedChatContentTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String) = ChatRenderItem.Single(
        UiMessage(id = id, role = "user", content = id, timestamp = "2026-09-07T00:00:00Z"),
        GroupPosition.None,
    )

    @Test fun targetLookupIncludesLiveAndPlaceholderOffsetsWithoutLoadingMissingHistory() {
        val rows = listOf(row("newer"), row("target"), row("older"))
        org.junit.Assert.assertEquals(8, residentTargetIndex(rows, "target", 2, 5))
        org.junit.Assert.assertNull(residentTargetIndex(rows, "missing", 2, 5))
    }

    @Test fun dateBoundaryRequiresResidentOlderRowAndValidDifferentDay() {
        val newer = row("newer")
        val older = row("older").copy(message = row("older").message.copy(timestamp = "2026-09-06T23:00:00Z"))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 9, 7), pagedBoundaryDate(newer, older))
        org.junit.Assert.assertNull(pagedBoundaryDate(newer, null))
        org.junit.Assert.assertNull(pagedBoundaryDate(newer, row("same day")))
        org.junit.Assert.assertNull(pagedBoundaryDate(newer.copy(message = newer.message.copy(timestamp = "invalid")), older))
    }

    @Test fun missingTargetFeedbackRequiresEngineConfirmation() {
        val missing = MutableStateFlow<String?>(null)
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from(listOf(row("unrelated")))), MutableStateFlow(emptyList()), {}, missing,
        )
        compose.setContent {
            LettaChatTheme {
                CompositionLocalProvider(LocalChatPagingPresentation provides presentation) {
                    ChatContent(
                        state = ChatUiState(),
                        callbacks = ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {},
                            onLoadOlderMessages = { error("Must not search sequentially") },
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        ),
                        appearance = ChatContentAppearance(scrollToMessageId = "target"),
                    )
                }
            }
        }
        compose.onNodeWithText("Message not found").assertDoesNotExist()
        compose.runOnIdle { missing.value = "stale target" }
        compose.onNodeWithText("Message not found").assertDoesNotExist()
        compose.runOnIdle { missing.value = "target" }
        compose.onNodeWithText("Message not found").assertIsDisplayed()
    }

    @Test fun targetViewportDoesNotFollowLiveUpdatesUntilLatestRequested() {
        val live = MutableStateFlow<List<ChatRenderItem>>(emptyList())
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from((0..80).map { row("row-$it") })), live, {},
        )
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                    ), ChatContentAppearance(scrollToMessageId = "row-50"),
                )
            }
        }
        compose.onNodeWithText("row-50").assertIsDisplayed()
        compose.runOnIdle { live.value = listOf(row("live-new").copy(message = row("live-new").message.copy(role = "assistant"))) }
        compose.onNodeWithText("row-50").assertIsDisplayed()
        compose.onNodeWithText("Scroll to latest").performClick()
        compose.onNodeWithText("live-new").assertIsDisplayed()
        compose.runOnIdle { live.value = listOf(row("live-updated")) }
        compose.onNodeWithText("live-updated").assertIsDisplayed()
    }

    @Test fun pinchCommitsScaleThroughBothHostCallbacks() {
        var active = 1f
        var persisted = 1f
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from(listOf(row("message")))), MutableStateFlow(emptyList()), {},
        )
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        onActiveFontScaleChange = { active = it }, onFontScaleChange = { persisted = it },
                    ), ChatContentAppearance(),
                )
            }
        }
        compose.onRoot().performTouchInput {
            pinch(Offset(center.x - 30, center.y), Offset(center.x + 30, center.y),
                Offset(center.x - 70, center.y), Offset(center.x + 70, center.y))
        }
        compose.runOnIdle {
            org.junit.Assert.assertTrue(active > 1f)
            org.junit.Assert.assertEquals(active, persisted)
            org.junit.Assert.assertTrue(active <= 1.6f)
        }
    }

    @Test fun sameConversationNewGenerationClosesAndReplacesPresentation() {
        val binding = ChatPagingBinding()
        var closes = 0
        fun create() = ChatPagingPresentation(
            flowOf(PagingData.empty()), MutableStateFlow(emptyList()), { closes++ },
        )
        val first = binding.select("conversation", 1, ::create)
        org.junit.Assert.assertSame(first, binding.select("conversation", 1) { error("Must reuse current generation") })
        val second = binding.select("conversation", 2, ::create)
        org.junit.Assert.assertNotSame(first, second)
        org.junit.Assert.assertEquals(1, closes)
        val third = binding.select("other", 2, ::create)
        org.junit.Assert.assertNotSame(second, third)
        org.junit.Assert.assertEquals(2, closes)
        binding.close()
        binding.close()
        org.junit.Assert.assertEquals(3, closes)
        org.junit.Assert.assertNull(binding.presentation)
    }

    @Test fun conversationRoundTripRestoresStableMessageAndIgnoresClosedGenerationWrites() {
        val binding = ChatPagingBinding()
        fun create() = ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {})
        val first = binding.select("a", 1, ::create)
        first.saveViewport(ChatPagingViewport("stable-message", 37))
        binding.select("b", 2, ::create).saveViewport(ChatPagingViewport("other-message", 11))
        first.saveViewport(ChatPagingViewport("stale-message", 0))
        org.junit.Assert.assertEquals("stable-message", binding.target("a"))
        val restored = binding.select("a", 3, ::create)
        org.junit.Assert.assertEquals(ChatPagingViewport("stable-message", 37), restored.viewport)
        org.junit.Assert.assertEquals("other-message", binding.target("b"))
    }

    @Test fun followingViewportOpensTailInsteadOfOldAnchor() {
        val binding = ChatPagingBinding()
        fun create() = ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {})
        binding.select("a", 1, ::create).saveViewport(ChatPagingViewport("old-tail", 0, following = true))
        binding.close()
        org.junit.Assert.assertNull(binding.target("a"))
        org.junit.Assert.assertTrue(binding.select("a", 2, ::create).viewport!!.following)
    }

    @Test fun explicitRouteOverridesSavedFollowingOnceThenRoundTripKeepsTailIntent() {
        val binding = ChatPagingBinding()
        val targets = mutableListOf<String?>()
        fun create(target: String?) = ChatPagingPresentation(
            flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {},
        ).also { targets += target }
        binding.selectRoute("a", 1, null, {}, ::create)
            .saveViewport(ChatPagingViewport("old-tail", 0, following = true))
        val search = binding.selectRoute("a", 2, "search", {}, ::create)
        org.junit.Assert.assertEquals("search", targets.last())
        org.junit.Assert.assertEquals("search", search.routeTarget)
        org.junit.Assert.assertNull(search.viewport)
        search.saveViewport(ChatPagingViewport("new-tail", 0, following = true))
        binding.close()
        val restored = binding.selectRoute("a", 3, "search", {}, ::create)
        org.junit.Assert.assertNull(targets.last())
        org.junit.Assert.assertNull(restored.routeTarget)
        org.junit.Assert.assertTrue(restored.viewport!!.following)
    }

    @Test fun immutableRouteIsNotAppliedToAnotherConversationOnRoundTrip() {
        val binding = ChatPagingBinding()
        val targets = mutableListOf<String?>()
        fun create(target: String?) = ChatPagingPresentation(
            flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {},
        ).also { targets += target }
        binding.selectRoute("a", 1, "search-a", {}, ::create)
            .saveViewport(ChatPagingViewport("anchor-a", 12))
        val other = binding.selectRoute("b", 2, "search-a", {}, ::create)
        org.junit.Assert.assertNull(other.routeTarget)
        other.saveViewport(ChatPagingViewport("anchor-b", 24))
        val restored = binding.selectRoute("a", 3, "search-a", {}, ::create)
        org.junit.Assert.assertNull(restored.routeTarget)
        org.junit.Assert.assertEquals(listOf("search-a", null, "anchor-a"), targets)
        org.junit.Assert.assertEquals(ChatPagingViewport("anchor-a", 12), restored.viewport)
    }

    @Test fun deletedAnchorReselectsEngineTailAndRejectsStaleRequests() {
        val binding = ChatPagingBinding()
        val targets = mutableListOf<String?>()
        var closes = 0
        var published: ChatPagingPresentation? = null
        fun create(target: String?) = ChatPagingPresentation(
            flowOf(PagingData.empty()), MutableStateFlow(emptyList()), { closes++ },
        ).also { targets += target }
        binding.selectRoute("a", 1, null, {}, ::create)
            .saveViewport(ChatPagingViewport("deleted", 28))
        val around = binding.selectRoute("a", 2, null, { published = it }, ::create)
        org.junit.Assert.assertEquals("deleted", targets.last())
        around.requestTail()
        org.junit.Assert.assertEquals(listOf(null, "deleted", null), targets)
        org.junit.Assert.assertSame(binding.presentation, published)
        org.junit.Assert.assertNotSame(around, published)
        org.junit.Assert.assertNull(published!!.viewport)
        org.junit.Assert.assertEquals(2, closes)
        around.requestTail()
        around.saveViewport(ChatPagingViewport("stale", 0))
        org.junit.Assert.assertEquals(3, targets.size)
        org.junit.Assert.assertNull(binding.target("a"))
    }

    @Test fun stableAnchorRestoresInAChangedBoundedWindow() {
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from((30..90).map { row("row-$it") })), MutableStateFlow(emptyList()), {},
        ).also { it.viewport = ChatPagingViewport("row-70", 0) }
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = { error("No sequential search") },
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                    ), ChatContentAppearance(),
                )
            }
        }
        compose.onNodeWithText("row-70").assertIsDisplayed()
        compose.onNodeWithText("Scroll to latest").assertIsDisplayed()
    }

    @Test fun missingRestoreAnchorRequestsFreshTailRatherThanAroundWindowIndexZero() {
        val binding = ChatPagingBinding()
        val missing = MutableStateFlow<String?>(null)
        val targets = mutableListOf<String?>()
        var current by androidx.compose.runtime.mutableStateOf<ChatPagingPresentation?>(null)
        fun create(target: String?) = ChatPagingPresentation(
            flowOf(PagingData.from(listOf(row(if (target == null) "actual tail" else "around window")))),
            MutableStateFlow(emptyList()), {}, missing,
        ).also { targets += target }
        binding.selectRoute("a", 1, null, {}, ::create).saveViewport(ChatPagingViewport("deleted", 0))
        current = binding.selectRoute("a", 2, null, { current = it }, ::create)
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    current!!, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = { error("No history walk") },
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                    ), ChatContentAppearance(),
                )
            }
        }
        compose.onNodeWithText("around window").assertIsDisplayed()
        compose.runOnIdle { missing.value = "deleted" }
        compose.onNodeWithText("actual tail").assertIsDisplayed()
        compose.onNodeWithText("around window").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf(null, "deleted", null), targets) }
    }

    @Test fun canonicalPresentationCancellationDetachesWithoutRetiringWriter() = kotlinx.coroutines.test.runTest {
        val coordinator = io.mockk.mockk<com.letta.mobile.data.timeline.CanonicalTimelineCoordinator>()
        val owner = io.mockk.mockk<com.letta.mobile.data.timeline.CanonicalTimelineCoordinator.Owner>()
        val lease = io.mockk.mockk<com.letta.mobile.data.timeline.CanonicalTimelineCoordinator.Presentation>()
        io.mockk.coEvery { coordinator.attach(owner, null) } returns lease
        io.mockk.coEvery { coordinator.detach(lease) } returns Unit
        var closed = 0
        val presentation = ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), { closed++ })
        val job = launch {
            ChatPagingHost().attachCanonicalPresentation(coordinator, owner, null, { presentation }) {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        org.junit.Assert.assertEquals(1, closed)
        io.mockk.coVerify(exactly = 1) { coordinator.detach(lease) }
        io.mockk.coVerify(exactly = 0) { coordinator.retire(any()) }
    }

    @Test fun hostIsDisabledWithoutEngineBinding() {
        org.junit.Assert.assertNull(ChatPagingHost().select)
    }

    @Test fun openingAndErrorStatesSkipLegacyPagerAndRetryOnlyTheCanonicalAction() {
        var retried = 0
        var mode by androidx.compose.runtime.mutableStateOf("opening")
        compose.setContent {
            LettaChatTheme {
                val presentation = if (mode == "opening") {
                    ChatPagingPresentation(
                        flowOf(PagingData.from(listOf(row("hidden opening")))),
                        MutableStateFlow(listOf(row("hidden live"))), {},
                        opening = true,
                    )
                } else {
                    ChatPagingPresentation(
                        flowOf(PagingData.from(listOf(row("hidden error")))), MutableStateFlow(emptyList()), {},
                        opening = false, openError = "Could not open conversation", retryOpen = { retried++ },
                    )
                }
                PagedChatMessageList(
                    presentation, ChatUiState(messages = persistentListOf(row("legacy").message)),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {},
                        onLoadOlderMessages = { error("Legacy pager must not run") },
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                    ), ChatContentAppearance(),
                )
            }
        }
        compose.onNodeWithText("Opening conversation...").assertIsDisplayed()
        compose.onNodeWithText("hidden opening").assertDoesNotExist()
        compose.onNodeWithText("legacy").assertDoesNotExist()
        compose.runOnIdle { mode = "error" }
        compose.onNodeWithText("Could not open conversation").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, retried) }
        compose.onNodeWithText("hidden error").assertDoesNotExist()
    }

    @Test fun flaggedContentRendersPagedAndLiveRowsInsteadOfLegacyMessages() {
        val live = MutableStateFlow<List<ChatRenderItem>>(listOf(row("live row")))
        val presentation = ChatPagingPresentation(flowOf(PagingData.from(listOf(row("settled row")))), live, {})
        compose.setContent {
            LettaChatTheme {
                CompositionLocalProvider(LocalChatPagingPresentation provides presentation) {
                    ChatContent(
                        state = ChatUiState(messages = persistentListOf(row("legacy row").message)),
                        callbacks = ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {},
                            onLoadOlderMessages = { error("Legacy pager must not run") },
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onDismissA2uiSurface = {}, onAttachmentImageTap = null,
                        ),
                        appearance = ChatContentAppearance(),
                    )
                }
            }
        }
        compose.onNodeWithText("live row").assertIsDisplayed()
        compose.onNodeWithText("settled row").assertIsDisplayed()
        compose.onNodeWithText("legacy row").assertDoesNotExist()
        compose.runOnIdle { live.value = listOf(row("updated live row")) }
        compose.onNodeWithText("updated live row").assertIsDisplayed()
        compose.onNodeWithText("settled row").assertIsDisplayed()
    }
}
