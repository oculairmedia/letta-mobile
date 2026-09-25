package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.TimelineEventKeys
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Bounded exact-event deduplication for fanout from shared bridge collectors.
 *
 * letta-mobile-qygvv.11: this is now the SAFETY NET. Exact duplicates are
 * dropped once at the transport ingest seam (`IngestFrameDeduplicator`, same
 * [TimelineEventKeys]), so [exactDuplicatesDropped] should read zero in normal
 * turns; a non-zero count means a duplicate source bypassed ingest.
 *
 * A message delta another coordinator already took is NOT a duplicate: every
 * collector of one bridge receives the same memoized event instance, and the
 * process-wide message window keeps first-coordinator-wins for it. That copy is
 * counted separately ([fanoutCopiesSkipped]) and does not log.
 */
internal class BridgeEventDeduplicator {
    private val eventLock = SynchronizedObject()
    private val eventKeys = ArrayDeque<String>()
    private val eventKeySet = mutableSetOf<String>()
    private var exactDuplicates = 0L
    private var fanoutCopies = 0L

    /** True duplicates this coordinator dropped (should be zero once ingest dedups). */
    val exactDuplicatesDropped: Long get() = synchronized(eventLock) { exactDuplicates }

    /** Same-frame copies already taken by another coordinator. */
    val fanoutCopiesSkipped: Long get() = synchronized(eventLock) { fanoutCopies }

    fun isDuplicate(event: WsTimelineEvent, fallbackConversationId: String?): Boolean {
        val key = TimelineEventKeys.key(event, fallbackConversationId) ?: return false
        val verdict = if (event is WsTimelineEvent.MessageDelta) {
            synchronized(sharedMessageEventLock) { rememberShared(key, event) }
        } else {
            synchronized(eventLock) {
                if (rememberBounded(key, eventKeys, eventKeySet)) Verdict.ExactDuplicate else Verdict.New
            }
        }
        record(verdict, event, key)
        return verdict != Verdict.New
    }

    private fun record(verdict: Verdict, event: WsTimelineEvent, key: String) {
        when (verdict) {
            Verdict.New -> return
            Verdict.FanoutCopy -> synchronized(eventLock) { fanoutCopies++ }
            Verdict.ExactDuplicate -> {
                synchronized(eventLock) { exactDuplicates++ }
                Telemetry.event(
                    "AdminChatVM", "ws.event.exactDuplicateDropped",
                    "eventType" to (event::class.simpleName ?: ""),
                    "keyHash" to key.hashCode().toString(),
                )
            }
        }
    }

    private fun rememberShared(key: String, event: WsTimelineEvent): Verdict {
        val previous = sharedMessageEventKeySet[key]
        if (previous != null) return if (previous === event) Verdict.FanoutCopy else Verdict.ExactDuplicate
        sharedMessageEventKeySet[key] = event
        sharedMessageEventKeys.addLast(key)
        while (sharedMessageEventKeys.size > MAX_SEEN_EVENTS) {
            sharedMessageEventKeySet.remove(sharedMessageEventKeys.removeFirst())
        }
        return Verdict.New
    }

    private fun rememberBounded(
        key: String,
        keys: ArrayDeque<String>,
        keySet: MutableSet<String>,
    ): Boolean {
        if (key in keySet) return true
        keySet += key
        keys.addLast(key)
        while (keys.size > MAX_SEEN_EVENTS) {
            keySet.remove(keys.removeFirst())
        }
        return false
    }

    private enum class Verdict { New, FanoutCopy, ExactDuplicate }

    private companion object {
        private const val MAX_SEEN_EVENTS = 512

        // Message frames are fanned out to multiple per-agent coordinators from
        // one bridge flow, so their exact-dedupe window remains process-wide.
        private val sharedMessageEventLock = SynchronizedObject()
        private val sharedMessageEventKeys = ArrayDeque<String>()

        // letta-mobile-463hb: PRE-EXISTING debt, not introduced here. The window
        // genuinely has to outlive any single coordinator, so the fix is to give
        // it an explicit owner -- tracked in 463hb -- not to swap the factory call
        // for one the rule happens not to match. Suppressed by owner decision so
        // a dedupe fix is not held hostage to an ownership refactor across ~45
        // construction sites. REMOVE THIS with the property.
        // qygvv.11: values hold the first-seen event instance so a same-frame
        // fanout copy (identical instance) is told apart from a true duplicate.
        @Suppress("NoProcessGlobalMutableState")
        private val sharedMessageEventKeySet = mutableMapOf<String, WsTimelineEvent>()
    }
}
