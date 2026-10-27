package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class TimelineIngestNotificationDispatcherTest {
    @Test
    fun parentCancellationStopsCallbackWithoutFailureTelemetryAndRunsCleanup() = runTest {
        withHistory { recorder ->
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            var continued = false
            val dispatcher = TimelineIngestNotificationDispatcher("conversation", listener {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cleaned = true
                }
            }, null)
            val job = launch {
                dispatcher.dispatch(notification())
                continued = true
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(cleaned)
            assertFalse(continued)
            assertTrue(recorder.snapshot().none { it.name == "streamSubscriber.listenerThrew" })
        }
    }

    @Test
    fun cancellationFromListenerResolutionIsNotConvertedToFailure() = runTest {
        withHistory { recorder ->
            val dispatcher = TimelineIngestNotificationDispatcher("conversation", null) {
                throw CancellationException("cancelled lookup")
            }
            assertFailsWith<CancellationException> { dispatcher.dispatch(notification()) }
            assertTrue(recorder.snapshot().isEmpty())
        }
    }

    @Test
    fun ordinaryCallbackAndResolutionErrorsRemainLoggedAndSwallowed() = runTest {
        withHistory { recorder ->
            val failure = IllegalStateException("failed lookup or callback")
            TimelineIngestNotificationDispatcher("conversation", listener { throw failure }, null).dispatch(notification())
            TimelineIngestNotificationDispatcher("conversation", null) { throw failure }.dispatch(notification())
            val errors = recorder.snapshot().filter { it.name == "streamSubscriber.listenerThrew" }
            assertEquals(2, errors.size)
            assertTrue(errors.all { it.throwable === failure })
            var called = false
            TimelineIngestNotificationDispatcher("conversation", listener { called = true }, null).dispatch(notification())
            assertTrue(called)
        }
    }

    private fun notification() = PendingIngestNotification("server", "assistant_message", "preview")

    private fun listener(block: suspend () -> Unit) = object : IngestedMessageListener {
        override suspend fun onMessageIngested(
            conversationId: String,
            serverId: String,
            messageType: String?,
            contentPreview: String?,
        ) = block()
    }

    private inline fun withHistory(block: (TelemetryRecorder) -> Unit) {
        val recorder = TelemetryRecorder()
        val enabled = Telemetry.enabled.get()
        val logcat = Telemetry.logcatEnabled.get()
        Telemetry.installRecorder(recorder).use {
            try {
                Telemetry.enabled.set(true)
                Telemetry.logcatEnabled.set(false)
                block(recorder)
            } finally {
                Telemetry.enabled.set(enabled)
                Telemetry.logcatEnabled.set(logcat)
            }
        }
    }
}
