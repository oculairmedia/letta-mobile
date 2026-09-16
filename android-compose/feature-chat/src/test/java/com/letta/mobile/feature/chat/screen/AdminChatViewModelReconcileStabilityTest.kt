package com.letta.mobile.feature.chat.screen

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.testutil.TestData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-rgn9u: the whole timeline flashed at every reconcile point (post-send,
 * run-completion, hydration). A reconcile re-publishes the conversation the user is already
 * reading; that re-publication used to bump [com.letta.mobile.data.chat.runtime.ChatSessionState]'s
 * `selectionGeneration`, which is exactly the key `beginTimelineObserver` compares to decide
 * whether its presentation is still current. A bumped generation retires the live presentation
 * (`_pagingPresentation.value = null`), publishes an `opening` placeholder — which
 * `PagedChatMessageList` renders as "Opening conversation..." **in place of the whole list** — and
 * then publishes a brand-new presentation, which `key(presentation)` rebuilds the LazyColumn and
 * its `rememberLazyListState` from. That is the flash, and every row re-composes with it.
 *
 * Asserting on the published presentations is the row-identity assertion the bug needs: the list
 * composable is keyed by presentation identity, so a stable identity across a reconcile *is* "no
 * row re-composition and no LazyLayout re-key", and it cannot pass by snapshotting once — the test
 * records the whole sequence of publications across the reconcile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AdminChatViewModelReconcileStabilityTest {

    @Test
    fun `re-hydrating or rotating the open conversation never retires its presentation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            var opens = 0
            var closes = 0
            val presentation = ChatPagingPresentation(
                settled = flowOf(PagingData.empty()),
                live = MutableStateFlow(emptyList()),
                close = { closes++ },
            )
            val host = ChatPagingHost().apply {
                openCanonical = { _, _, _, _ ->
                    opens++
                    presentation
                }
            }
            val vm = openedChatViewModel(host, TestData.agent("agent-reconcile", "Reconcile"), CONVERSATION_ID, "reconcile")
            viewModel = vm
            assertEquals("The conversation must be open before the reconcile", 1, opens)
            assertSame(presentation, vm.pagingPresentation.value)

            val published = mutableListOf<ChatPagingPresentation?>()
            val recorder = vm.viewModelScope.launch { vm.pagingPresentation.collect { published += it } }
            // The reconcile: re-hydrate the conversation already on screen, exactly as the
            // post-send, run-completion and conversation-open paths all do.
            vm.loadMessages()
            testScheduler.advanceUntilIdle()
            // The same reconcile arrives on device rotation: the Activity is recreated, the
            // ViewModel survives, and the resumed screen re-resolves the conversation already on
            // screen. letta-mobile-6bqi1 stopped that from re-entering Loading, but the paged
            // presentation was still retired underneath it.
            vm.onScreenPaused()
            vm.onScreenResumed()
            testScheduler.advanceUntilIdle()
            recorder.cancel()

            assertEquals("The reconcile must not reopen the conversation", 1, opens)
            assertEquals("The reconcile must not retire the live presentation", 0, closes)
            assertSame(presentation, vm.pagingPresentation.value)
            assertTrue(
                "The list must never be replaced by a teardown or an opening placeholder: $published",
                published.all { it === presentation },
            )
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val CONVERSATION_ID = "conversation-reconcile"
    }
}
