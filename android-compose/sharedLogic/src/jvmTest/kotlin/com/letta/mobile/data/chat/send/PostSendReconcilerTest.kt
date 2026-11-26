package com.letta.mobile.data.chat.send

import com.letta.mobile.data.timeline.headless.HeadlessTimelineStore
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.textOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scheduled post-send reconcile must live and die with the scope that owns it - on device, the
 * chat screen's viewModelScope that chat teardown cancels. Cancelling the owner scope is the only
 * stop signal: once its job is cancelled and joined, the delayed read must never run and no
 * ws.postSendReconcile telemetry may fire, or the reconcile keeps writing into whichever
 * [Telemetry] recorder a later test installs (the cross-test leak that failed
 * TelemetryLifecycleAfterAbandonedTransportTest in CI).
 */
class PostSendReconcilerTest {

    @Test
    fun aScheduledReconcileRunsWhileItsOwnerLivesAndDiesWithItsScope() = runBlocking {
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            // Control: while the owner lives, the delayed read runs and reports success.
            val livingOwner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            PostSendReconciler(livingOwner, AGENT, HeadlessTimelineStore()) { longArrayOf(RECONCILE_DELAY_MS) }
                .schedule(LIVING_CONVERSATION, LIVING_OTID)
            delay(WAIT_PAST_DELAY_MS)

            // The owner's scope ends before the delay elapses (what viewModelScope cancellation
            // does when a chat screen clears).
            val dyingOwner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            PostSendReconciler(dyingOwner, AGENT, HeadlessTimelineStore()) { longArrayOf(RECONCILE_DELAY_MS) }
                .schedule(DYING_CONVERSATION, DYING_OTID)
            dyingOwner.coroutineContext[Job]!!.cancelAndJoin()
            // The join proves every coroutine on the scope finished executing; waiting past the
            // schedule delay would also catch a read that escaped the scope (an internal
            // GlobalScope launch), which would fire during the wait.
            delay(WAIT_PAST_DELAY_MS)

            assertEquals(
                1,
                recorder.snapshot().count { event ->
                    event.name == OK_EVENT && event.attrs[CONVERSATION_ID_ATTR]?.textOrNull == LIVING_CONVERSATION
                },
                "control: the live reconcile reported ok",
            )
            assertTrue(
                recorder.snapshot().none { event -> event.attrs[CONVERSATION_ID_ATTR]?.textOrNull == DYING_CONVERSATION },
                "no reconcile telemetry may fire after the owner's scope died",
            )
        }
    }

    private companion object {
        const val AGENT = "reconcile-agent"
        const val LIVING_CONVERSATION = "conv-owner-living"
        const val DYING_CONVERSATION = "conv-owner-cancelled"
        const val LIVING_OTID = "otid-living"
        const val DYING_OTID = "otid-dying"
        const val OK_EVENT = "ws.postSendReconcile.ok"
        const val CONVERSATION_ID_ATTR = "conversationId"
        const val RECONCILE_DELAY_MS = 50L
        const val WAIT_PAST_DELAY_MS = 400L
    }
}
