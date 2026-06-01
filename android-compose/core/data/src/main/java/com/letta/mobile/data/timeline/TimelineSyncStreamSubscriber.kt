package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.stream.SseParser
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Runs the persistent SSE stream subscriber for a single conversation.
 * Handles backoff, reconnection, silence timeouts, and external-run reconciliation.
 */
internal suspend fun runStreamSubscriber(
    conversationId: String,
    messageApi: com.letta.mobile.data.api.MessageApi,
    activeStreamCount: AtomicInteger,
    events: MutableSharedFlow<TimelineSyncEvent>,
    seenRunIds: MutableSet<String>,
    streamSilenceTimeoutMs: Long,
    reconcileForExternalRun: suspend (String) -> Unit,
    ingestStreamEvent: suspend (LettaMessage) -> Unit,
    setStreamActive: suspend (Boolean) -> Unit,
) {
    // Note: no foreground/subscriptionCount gate. The subscriber runs for
    // the full lifetime of this TimelineSyncLoop (which is a @Singleton-
    // scoped cache in TimelineRepository). Process lifetime is extended
    // via ChatPushService (a foreground service) so messages are delivered
    // even when the screen is off / app backgrounded. This is the push
    // architecture agreed on 2026-04-18 after the subscriptionCount gate
    // proved too conservative — messages never arrived when the chat
    // screen was not foregrounded.
    var backoffMs = STREAM_BACKOFF_START_MS
    var runOpenedAtMs = 0L
    var runEventsCount = 0
    var runHeartbeatCount = 0
    while (currentCoroutineContext().isActive) {
        try {
            val channel = messageApi.streamConversation(ConversationId(conversationId))
            val activeStreamCountOnOpen = activeStreamCount.incrementAndGet()
            var activeStreamCountAfterClose = activeStreamCountOnOpen
            var streamTimedOut = false
            var timedOutSilenceMs = 0L
            try {
                runOpenedAtMs = System.currentTimeMillis()
                runEventsCount = 0
                runHeartbeatCount = 0
                var lastHeartbeatTelemetryAtMs = 0L
                setStreamActive(true)
                events.emit(TimelineSyncEvent.StreamSubscriberOpened)
                // letta-mobile-mge5.6/jmzq.4: per-phase telemetry for
                // observability, including active persistent stream count
                // so budget/dispatcher starvation can be verified.
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.opened",
                    "conversationId" to conversationId,
                    "activeStreamCount" to activeStreamCountOnOpen,
                )
                var lastLivenessAtMs = runOpenedAtMs
                coroutineScope {
                    val frames = SseParser.parseFrames(channel).produceIn(this)
                    try {
                        while (currentCoroutineContext().isActive) {
                        val result = withTimeoutOrNull(streamSilenceTimeoutMs) {
                            frames.receiveCatching()
                        }
                        if (result == null) {
                            val now = System.currentTimeMillis()
                            timedOutSilenceMs = now - lastLivenessAtMs
                            streamTimedOut = true
                            Telemetry.event(
                                "TimelineSync", "streamSubscriber.silenceTimeout",
                                "conversationId" to conversationId,
                                "silenceMs" to timedOutSilenceMs,
                                "timeoutMs" to streamSilenceTimeoutMs,
                                "eventsReceived" to runEventsCount,
                                "heartbeatsReceived" to runHeartbeatCount,
                            )
                            channel.cancel(CancellationException("SSE silence timeout"))
                            break
                        }

                        val frame = result.getOrNull() ?: break
                        lastLivenessAtMs = System.currentTimeMillis()
                        when (frame) {
                            SseFrame.Heartbeat -> {
                                runHeartbeatCount++
                                val now = lastLivenessAtMs
                                if (lastHeartbeatTelemetryAtMs == 0L ||
                                    now - lastHeartbeatTelemetryAtMs >= HEARTBEAT_TELEMETRY_MIN_INTERVAL_MS
                                ) {
                                    lastHeartbeatTelemetryAtMs = now
                                    Telemetry.event(
                                        "TimelineSync", "streamSubscriber.heartbeat",
                                        "conversationId" to conversationId,
                                        "sinceOpenMs" to (now - runOpenedAtMs),
                                        "heartbeatsReceived" to runHeartbeatCount,
                                    )
                                }
                            }
                            is SseFrame.Message -> {
                                val message = frame.message
                                // letta-mobile-mge5.6: raw-event counter for rate metrics.
                                runEventsCount++
                                Telemetry.event(
                                    "TimelineSync", "streamSubscriber.eventReceived",
                                    "conversationId" to conversationId,
                                    "messageType" to (message.messageType ?: "?"),
                                    "runId" to (message.runId ?: "<null>"),
                                )
                                // Detect a new run_id: this is a run we didn't start
                                // ourselves (the locally-initiated send path tracks its
                                // own otids). BLOCK on the reconcile for the first frame
                                // so the user_message that started the run lands in the
                                // timeline BEFORE the assistant frames — otherwise the
                                // user bubble appears below the reply. Subsequent frames
                                // for the same run hit seenRunIds.add()=false and skip
                                // the reconcile.  letta-mobile-mge5 for Matrix-originated
                                // messages.
                                val runId = message.runId
                                if (runId != null && seenRunIds.add(runId)) {
                                    val capturedRunId = runId
                                    runCatching { reconcileForExternalRun(capturedRunId) }.onFailure { t ->
                                        Telemetry.error(
                                            "TimelineSync", "externalRunReconcile.failed", t,
                                            "conversationId" to conversationId,
                                            "runId" to capturedRunId,
                                        )
                                    }
                                }
                                ingestStreamEvent(message)
                            }
                            is SseFrame.RawEvent -> Unit
                            SseFrame.Done -> Unit
                        }
                    }
                    } finally {
                        frames.cancel()
                    }
                }
            } finally {
                activeStreamCountAfterClose = activeStreamCount.decrementAndGet()
                setStreamActive(false)
            }
            if (streamTimedOut) {
                val reconnectDelayMs = STREAM_BACKOFF_START_MS + Random.nextLong(STREAM_BACKOFF_START_MS)
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.watchdogReconnect",
                    "conversationId" to conversationId,
                    "reason" to "silenceTimeout",
                    "silenceMs" to timedOutSilenceMs,
                    "delayMs" to reconnectDelayMs,
                    "activeStreamCount" to activeStreamCountAfterClose,
                )
                delay(reconnectDelayMs)
                continue
            }
            // Stream closed cleanly: run finished. Reset backoff.
            events.emit(TimelineSyncEvent.StreamSubscriberClosed)
            // letta-mobile-mge5.6: closed-event tracking — run duration
            // and event-count let Grafana compute delivery rates and
            // detect runs that close with zero events (mechanism broken).
            Telemetry.event(
                "TimelineSync", "streamSubscriber.closed",
                "conversationId" to conversationId,
                "durationMs" to (System.currentTimeMillis() - runOpenedAtMs),
                "eventsReceived" to runEventsCount,
                "heartbeatsReceived" to runHeartbeatCount,
                "activeStreamCount" to activeStreamCountAfterClose,
            )
            backoffMs = STREAM_BACKOFF_START_MS
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoActiveRunException) {
            // No active run: back off exponentially. This is the expected
            // idle path. Counted at INFO so Grafana can compute the
            // activity-density ratio (eventReceived / idle404).
            Telemetry.event(
                "TimelineSync", "streamSubscriber.idle404",
                "conversationId" to conversationId,
                "backoffMs" to backoffMs,
            )
            delay(backoffMs)
            // letta-mobile-qv6d: idle path uses the longer cap.
            backoffMs = (backoffMs * 2).coerceAtMost(STREAM_IDLE_BACKOFF_MAX_MS)
        } catch (e: ApiException) {
            // letta-mobile-t8q7: idle-pattern classification (No active runs /
            // EXPIRED / is now expired) is now done in MessageApi.streamConversation,
            // which routes those bodies via the stackless NoActiveRunException
            // path above. Anything that reaches here is a real server error.
            // letta-mobile-mge5.6: distinguish transient network / server errors
            // from the idle path. Grafana alerts on sustained networkError rate,
            // not on idle404.
            Telemetry.error(
                "TimelineSync", "streamSubscriber.networkError", e,
                "conversationId" to conversationId,
            )
            delay(STREAM_BACKOFF_MAX_MS)
        } catch (t: Throwable) {
            Telemetry.error(
                "TimelineSync", "streamSubscriber.networkError", t,
                "conversationId" to conversationId,
            )
            delay(STREAM_BACKOFF_MAX_MS)
        }
    }
}

// Extension function to produce a ReceiveChannel from a Flow
private fun <T> kotlinx.coroutines.flow.Flow<T>.produceIn(scope: CoroutineScope): ReceiveChannel<T> =
    scope.produce {
        collect { send(it) }
    }

// Constants used by the stream subscriber
private const val STREAM_BACKOFF_START_MS = 1000L
private const val STREAM_BACKOFF_MAX_MS = 8000L
private const val STREAM_IDLE_BACKOFF_MAX_MS = 32000L
private const val HEARTBEAT_TELEMETRY_MIN_INTERVAL_MS = 30000L
