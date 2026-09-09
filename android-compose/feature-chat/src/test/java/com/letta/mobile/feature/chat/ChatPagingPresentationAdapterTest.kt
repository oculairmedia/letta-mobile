package com.letta.mobile.feature.chat.screen

import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IsolatedChatPagingPresentationAdapterTest {
    @Test fun freshSearchAndTailUseNewFactoriesWithinOneSelectionGeneration() = runTest {
        val binding = ChatPagingBinding()
        val targets = mutableListOf<String?>()
        fun create(target: String?): ChatPagingPresentation {
            targets += target
            return createChatPagingPresentation(
                backgroundScope, flowOf(PagingData.empty()), MutableStateFlow(emptyList()),
                MutableStateFlow(null), {},
            )
        }
        val tail = binding.selectRoute("a", 1, null, {}, ::create)
        val search = binding.selectRoute("a", 1, "target", {}, ::create)
        assertNotSame(tail, search)
        assertNotSame(tail.settled, search.settled)
        assertSame(search, binding.selectRoute("a", 1, "target", {}, ::create))
        search.requestTail()
        assertNotSame(search, binding.presentation)
        assertEquals(listOf(null, "target", null), targets)
        binding.close()
    }

    @Test fun missingTargetRetainsCurrentPagingFlowAndLiveSnapshot() = runTest {
        val binding = ChatPagingBinding()
        val missing = MutableStateFlow<String?>(null)
        val live = MutableStateFlow<List<ChatRenderItem>>(emptyList())
        val view = binding.selectRoute("a", 1, "missing", {}, {
            createChatPagingPresentation(
                backgroundScope, flowOf(PagingData.empty()), live, missing, {},
            )
        })
        val settled = view.settled
        missing.value = "missing"
        assertSame(view, binding.presentation)
        assertSame(settled, view.settled)
        assertSame(live, view.live)
        assertSame(missing, view.missingTarget)
        assertEquals("missing", view.missingTarget.value)
        binding.close()
    }

    @Test fun residentSnapshotIsForwardedUnchangedAndIgnoredAfterDisposal() = runTest {
        val reports = mutableListOf<List<ChatRenderItem>>()
        val rows = mutableListOf<ChatRenderItem>()
        val view = createChatPagingPresentation(
            backgroundScope, flowOf(PagingData.empty()), MutableStateFlow(emptyList()),
            MutableStateFlow(null), { reports += it },
        )
        view.onResidentRows(rows)
        assertSame(rows, reports.single())
        view.close()
        view.close()
        view.onResidentRows(rows)
        assertEquals(1, reports.size)
    }

    @Test fun replacementAndDisposalCancelOnlyPresentationCacheJobs() = runTest {
        val binding = ChatPagingBinding()
        val ingestion = backgroundScope.launch { awaitCancellation() }
        var activeCaches = 0
        fun create() = createChatPagingPresentation(
            backgroundScope,
            flow<PagingData<ChatRenderItem>> {
                activeCaches++
                try {
                    emit(PagingData.empty())
                    awaitCancellation()
                } finally {
                    activeCaches--
                }
            },
            MutableStateFlow(emptyList()), MutableStateFlow(null), {},
        )
        val first = binding.select("a", 1, ::create)
        backgroundScope.launch { first.settled.collect() }
        runCurrent()
        assertEquals(1, activeCaches)
        val second = binding.select("b", 2, ::create)
        backgroundScope.launch { second.settled.collect() }
        runCurrent()
        assertNotSame(first, second)
        assertEquals(1, activeCaches)
        assertTrue(ingestion.isActive)
        binding.close()
        runCurrent()
        assertEquals(0, activeCaches)
        assertFalse(ingestion.isCancelled)
        ingestion.cancel()
    }
}
