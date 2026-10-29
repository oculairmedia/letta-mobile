@file:OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)

package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import com.letta.mobile.ui.chat.surface.timeline.ManualMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PROMPT = "Plan a taco night for six"

internal class SendLiftReadinessTest {
    private val main = ManualMainDispatcher()

    @BeforeTest
    fun installMain() = Dispatchers.setMain(main)

    @AfterTest
    fun removeMain() = Dispatchers.resetMain()

    @Test
    fun delayedHistoryFailsFixedSetupButReadinessStillSendsFromAwayToNewestEdge() {
        runDesktopComposeUiTest(width = SendLiftFrameRecorder.WIDTH, height = SendLiftFrameRecorder.HEIGHT) {
            // Old setup pumps 10 mount frames + 30 warmup frames. History cannot arrive until 41.
            val rig = PagedSendLiftRig.open(main, pairs = 12, initialHistoryAfterPumps = 41)
            try {
                val port = SendLiftPort(0, PROMPT, rig::send)
                SendLiftFrameRecorder(this, port, rig::pump).use { recorder ->
                    recorder.mount(SendLiftMount(paged = rig.presentation))
                    recorder.step(30)
                    // This is the compilable old behavior, not a probe that breaks a new symbol.
                    SendLiftListView(this) { port.promptKey }.use { list ->
                        val failure = assertFailsWith<IllegalStateException> { list.scrollAwayFromTheEdge() }
                        assertEquals("the page has no timeline list to scroll", failure.message)
                    }
                    recorder.scrollListAwayFromTheEdge()
                    assertTrue(recorder.frames.isEmpty(), "readiness and scrolling must precede captured send frames")
                    recorder.tapSend(PROMPT)
                    recorder.advance(40)
                    val frames = recorder.frames
                    assertEquals(42, frames.size, "keep baseline, tap frame, and every subsequent send frame")
                    assertEquals(SendLiftFrameRecorder.BASELINE_T, frames.first().t)
                    assertTrue(frames.first().firstVisibleItemIndex > 0, "the send actually starts away from newest")
                    assertTrue(frames.last().promptSlot != null, "the pending prompt actually reached the list")
                    val violations = listEndsAtNewestEdge(frames)
                    assertTrue(violations.isEmpty(), "send must end at newest edge: $violations")
                }
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun absentListFailsBoundedlyWithoutSendingOrRecording() {
        runDesktopComposeUiTest(width = SendLiftFrameRecorder.WIDTH, height = SendLiftFrameRecorder.HEIGHT) {
            val rig = PagedSendLiftRig.open(main, pairs = 0)
            try {
                val port = SendLiftPort(0, PROMPT, rig::send)
                SendLiftFrameRecorder(this, port, rig::pump).use { recorder ->
                    recorder.mount(SendLiftMount(paged = rig.presentation))
                    val failure = assertFailsWith<IllegalStateException> {
                        recorder.awaitScrollableList(timeoutMillis = 1L)
                    }
                    assertTrue(failure.message.orEmpty().contains("missing semantics node or no visible items"))
                    assertTrue(failure.cause is ComposeTimeoutException, "only readiness timeout is translated")
                    assertTrue(recorder.frames.isEmpty(), "never-ready setup cannot produce a successful send recording")
                }
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun unresolvableListStateFailsImmediatelyInsteadOfTimingOut() {
        runDesktopComposeUiTest(width = SendLiftFrameRecorder.WIDTH, height = SendLiftFrameRecorder.HEIGHT) {
            setContent {
                Box(Modifier.size(100.dp).testTag(ChatTimelineTags.LIST).semantics {
                    verticalScrollAxisRange = ScrollAxisRange(value = { 0f }, maxValue = { 1f })
                })
            }
            SendLiftFrameRecorder(this, SendLiftPort(0, PROMPT)).use { recorder ->
                val failure = assertFailsWith<IllegalStateException> {
                    recorder.awaitScrollableList(timeoutMillis = 1L)
                }
                assertEquals("no LazyListState reachable from the list's scroll semantics", failure.message)
                assertEquals(null, failure.cause, "a broken state finder must not become a readiness timeout")
                assertTrue(recorder.frames.isEmpty())
            }
        }
    }
}
