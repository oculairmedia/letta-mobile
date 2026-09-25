package com.letta.mobile.data.chat.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRosterRefresherTest {
    private fun TestScope.refresher(
        onRefresh: suspend () -> Unit,
    ) = ConversationRosterRefresher(
        scope = backgroundScope,
        refresh = onRefresh,
        debounce = DEBOUNCE,
        interval = INTERVAL,
    )

    @Test
    fun burstOfRequestsCoalescesIntoOneRefresh() = runTest {
        var refreshes = 0
        val subject = refresher { refreshes++ }
        subject.start()

        repeat(5) { subject.requestRefresh() }
        advanceTimeBy(DEBOUNCE - 1.milliseconds)
        subject.requestRefresh()
        runCurrent()
        assertEquals(0, refreshes, "nothing runs before the debounce elapses")

        advanceTimeBy(DEBOUNCE)
        runCurrent()
        assertEquals(1, refreshes)
    }

    @Test
    fun requestsDuringARunningRefreshYieldExactlyOneFollowUp() = runTest {
        var refreshes = 0
        val gate = CompletableDeferred<Unit>()
        val subject = refresher {
            refreshes++
            if (refreshes == 1) gate.await()
        }
        subject.start()

        subject.requestRefresh()
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        runCurrent()
        assertEquals(1, refreshes)

        repeat(3) { subject.requestRefresh() }
        gate.complete(Unit)
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        runCurrent()
        assertEquals(2, refreshes)

        advanceTimeBy(DEBOUNCE * 4)
        runCurrent()
        assertEquals(2, refreshes, "no further refreshes without new requests")
    }

    @Test
    fun periodicTickRefreshesOnlyWhileActive() = runTest {
        var refreshes = 0
        val subject = refresher { refreshes++ }
        subject.start()

        advanceTimeBy(INTERVAL + DEBOUNCE + 1.milliseconds)
        assertEquals(1, refreshes)

        subject.setActive(false)
        advanceTimeBy(INTERVAL * 3)
        assertEquals(1, refreshes, "an inactive host is not polled")

        subject.setActive(true)
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        assertEquals(2, refreshes, "regaining focus refreshes immediately")
    }

    @Test
    fun failingRefreshDoesNotStopLaterRefreshes() = runTest {
        var attempts = 0
        val subject = refresher {
            attempts++
            error("backend unreachable")
        }
        subject.start()

        subject.requestRefresh()
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        subject.requestRefresh()
        advanceTimeBy(DEBOUNCE + 1.milliseconds)

        assertEquals(2, attempts)
    }

    @Test
    fun stopCancelsTicksAndDropsPendingRequests() = runTest {
        var refreshes = 0
        val subject = refresher { refreshes++ }
        subject.start()

        subject.requestRefresh()
        subject.stop()
        advanceTimeBy(INTERVAL * 2)

        assertEquals(0, refreshes)
        assertFalse(subject.isRunning)

        subject.start()
        subject.requestRefresh()
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        assertEquals(1, refreshes, "a restarted refresher serves requests again")
    }

    private companion object {
        val DEBOUNCE = 500.milliseconds
        val INTERVAL = 30.seconds
    }
}
