package com.letta.mobile.data.timeline

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail

/** Shared by the real-Pager tests: a presenter that records rows, and a real-time wait. */
internal class RecordingPresenter<T : Any> : PagingDataPresenter<T>(Dispatchers.Default, null) {
    override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) = Unit

    suspend fun awaitIdle() {
        val idle = withTimeoutOrNull(5_000) {
            loadStateFlow.first { states ->
                states != null && states.refresh is LoadState.NotLoading &&
                    states.prepend is LoadState.NotLoading && states.append is LoadState.NotLoading
            }
        }
        if (idle == null) fail("Paging did not settle: ${loadStateFlow.value}")
    }

    /**
     * Waits for the row count, and names what the pipeline had done when it did not arrive.
     * Polls the condition rather than waiting on [onPagesUpdatedFlow]: that flow has no replay,
     * so a page update that landed before this call subscribed would never be observed and the
     * wait would time out with the rows already present (CI, 2026-09-24).
     */
    suspend fun awaitRows(expected: Int, detail: () -> String) {
        val settled = withTimeoutOrNull(10_000) {
            while (size != expected) delay(10)
            true
        }
        if (settled == null) {
            fail("presenter never reached $expected rows: size=$size loadState=${loadStateFlow.value} ${detail()}")
        }
    }
}

/**
 * A transport whose every call fails the test. Delegate to it and override only the calls a test
 * expects, so a fake never repeats the whole transport surface.
 */
internal fun unexpectedTimelineTransport(): TimelineTransport = java.lang.reflect.Proxy.newProxyInstance(
    TimelineTransport::class.java.classLoader,
    arrayOf(TimelineTransport::class.java),
) { _, method, _ -> fail("unexpected transport call: ${method.name}") } as TimelineTransport

/** Real-time wait for async pipeline work that has no flow to observe. */
internal suspend fun awaitCondition(detail: () -> String, condition: () -> Boolean) {
    val met = withTimeoutOrNull(10_000) {
        while (!condition()) delay(10)
        true
    }
    if (met == null) fail("condition never held: ${detail()}")
}
