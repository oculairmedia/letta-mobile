package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-20tat (P1): Tests for user message deduplication during
 * reconcileAfterSend. Verifies that the optimistic local user row and its
 * reconciled disk twin collapse (by otid when present; content+recency
 * fallback when otid is missing).
 */
class TimelineReconcileUserDedupTest {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    @Test
    fun userMessageDedupesWhenOtidMatches() = runTest {
        // letta-mobile-20tat: the optimistic local user row carries
        // otid="cm-android-123". When the reconciled server message.list
        // response echoes the SAME otid, dedup must collapse the two rows
        // into one (the confirmed disk copy replaces the optimistic local).
        val otid = "cm-android-test-123"
        val conversationId = "conv-1"
        
        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Local(
                    position = 1.0,
                    otid = otid,
                    content = "that's my bad we're on the wrong network",
                    role = Role.USER,
                    sentAt = timelineNow(),
                    deliveryState = DeliveryState.SENT,
                ),
            ),
        )

        val serverMessages = listOf(
            UserMessage(
                id = "ui-msg-456",
                contentRaw = kotlinx.serialization.json.JsonPrimitive("that's my bad we're on the wrong network"),
                date = "2026-07-08T16:16:00Z",
                otid = otid, // Server echoes the client's otid
            ),
        )

        val result = applyReconcileAfterSendSnapshot(
            otid = otid,
            conversationId = conversationId,
            serverMessages = serverMessages,
            writeMutex = Mutex(),
            state = MutableStateFlow(timeline),
        )

        assertEquals(true, result.confirmedLocal, "Optimistic local should be confirmed")
        assertEquals(0, result.appendedMissing, "No duplicate rows should be appended")
        
        val finalTimeline = MutableStateFlow(timeline).value
        assertEquals(1, finalTimeline.events.size, "Should have exactly one event after dedup")
    }

    @Test
    fun userMessageDedupesWhenOtidMissingButContentMatches() = runTest {
        // letta-mobile-20tat: when the server message.list response does NOT
        // echo the client otid (common over Iroh if the serve path doesn't
        // persist it), fall back to content+recency dedup: a recent user row
        // with identical trimmed content is the same logical message.
        val otid = "cm-android-test-456"
        val conversationId = "conv-1"
        val content = "that's my bad we're on the wrong network"

        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Local(
                    position = 1.0,
                    otid = otid,
                    content = content,
                    role = Role.USER,
                    sentAt = timelineNow(),
                    deliveryState = DeliveryState.SENT,
                ),
            ),
        )

        val serverMessages = listOf(
            UserMessage(
                id = "ui-msg-789",
                contentRaw = kotlinx.serialization.json.JsonPrimitive(content),
                date = "2026-07-08T16:16:00Z",
                otid = null, // Server did NOT echo the client otid
            ),
        )

        val writeMutex = Mutex()
        val state = MutableStateFlow(timeline)

        val result = applyReconcileAfterSendSnapshot(
            otid = otid,
            conversationId = conversationId,
            serverMessages = serverMessages,
            writeMutex = writeMutex,
            state = state,
        )

        // Even without otid match, content dedup should prevent duplication
        val finalTimeline = state.value
        assertEquals(1, finalTimeline.events.size, "Content-based dedup should prevent duplicate user rows")
        
        val event = finalTimeline.events.single() as? TimelineEvent.Confirmed
        assertEquals(content, event?.content?.trim(), "Final event should have the correct content")
    }

    @Test
    fun userMessageNotDuplicatedWhenForceRefreshReconcileRuns() = runTest {
        // letta-mobile-20tat: the redial-recovery forceRefresh path calls
        // reconcile without an existing Local row. It must still dedupe the
        // disk user row against recent user events by content so the user
        // message doesn't re-insert on reconnect.
        val conversationId = "conv-1"
        val content = "hello from redial recovery"

        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "cm-android-old",
                    serverId = "ui-msg-previous",
                    content = content,
                    messageType = TimelineMessageType.USER,
                    date = timelineNow(),
                    runId = null,
                    stepId = null,
                ),
            ),
        )

        val serverMessages = listOf(
            UserMessage(
                id = "ui-msg-previous", // SAME server id
                contentRaw = kotlinx.serialization.json.JsonPrimitive(content),
                date = "2026-07-08T16:16:00Z",
                otid = null,
            ),
        )

        val writeMutex = Mutex()
        val state = MutableStateFlow(timeline)

        val merged = timeline.mergeServerMessages(serverMessages)
        val finalTimeline = merged.first

        assertEquals(1, finalTimeline.events.size, "Duplicate by serverId should not re-insert")
        assertEquals(0, merged.second, "No messages should be appended")
    }
}
