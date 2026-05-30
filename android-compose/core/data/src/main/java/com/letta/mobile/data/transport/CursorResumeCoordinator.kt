package com.letta.mobile.data.transport

import android.util.Log
import com.letta.mobile.data.timeline.ConversationCursorStore
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active run and conversation cursors, tracking hello resume replay
 * logic and orchestrating post-welcome recovery subscriptions.
 */
internal class CursorResumeCoordinator(
    private val scope: CoroutineScope,
    private val cursorStore: RunCursorStore,
    private val conversationCursorStore: ConversationCursorStore,
    private val json: Json,
) {
    private val resumedRunConversationIds = ConcurrentHashMap<String, String>()
    private val helloResumeAfterSeqByConversation = ConcurrentHashMap<String, Long>()
    private val helloResumeReplayCountsByConversation = ConcurrentHashMap<String, Long>()

    fun ensureLoaded() {
        cursorStore.ensureLoaded()
    }

    fun registerResumedRun(runId: String, conversationId: String) {
        resumedRunConversationIds[runId] = conversationId
    }

    fun removeResumedRun(runId: String): String? {
        return resumedRunConversationIds.remove(runId)
    }

    fun getResumedRunConversationId(runId: String): String? {
        return resumedRunConversationIds[runId]
    }

    fun recordCursor(conversationId: String, runId: String, seq: Long) {
        cursorStore.record(conversationId, runId, seq)
    }

    fun clearCursor(conversationId: String, runId: String) {
        cursorStore.clear(conversationId, runId)
    }

    fun clearResumedRunFromAllActive(runId: String) {
        cursorStore.allActiveRuns().forEach { (convId, runs) ->
            if (runs.containsKey(runId)) {
                cursorStore.clear(convId, runId)
            }
        }
        resumedRunConversationIds.remove(runId)
    }

    /**
     * Eagerly loads all active conversation cursors from storage to be included
     * in the initial WebSocket Hello frame.
     */
    suspend fun loadHelloResumeCursors(): List<ResumeCursor> {
        val cursors = runCatching { conversationCursorStore.getAllCursors() }
            .onFailure { Log.w(TAG, "Failed to load conversation cursors for hello resume: ${it.message}", it) }
            .getOrDefault(emptyMap())
            .filterValues { it >= 0L }
            .toSortedMap()
        helloResumeAfterSeqByConversation.clear()
        helloResumeReplayCountsByConversation.clear()
        helloResumeAfterSeqByConversation.putAll(cursors)
        if (cursors.isNotEmpty()) {
            Telemetry.event(
                "ChannelTransport", "helloResume.requested",
                "conversationCount" to cursors.size,
                "maxAfterSeq" to cursors.values.maxOrNull(),
            )
        }
        return cursors.map { (conversationId, afterSeq) ->
            ResumeCursor(conversationId = conversationId, afterSeq = afterSeq)
        }
    }

    /**
     * Extracts envelope (run_id, seq/seq_id, conversation_id) from the inbound message
     * and updates the local cursorStore.
     */
    fun recordCursorFromEnvelope(text: String, activeConversationForRun: (String) -> String?) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type in SKIP_CURSOR_TYPES) return
        val runId = obj["run_id"]?.jsonPrimitive?.contentOrNull ?: return
        val seq = obj["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: obj["seq_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return
        val convId = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
            ?: activeConversationForRun(runId)
            ?: resumedRunConversationIds[runId]
            ?: return
        cursorStore.record(convId, runId, seq)
    }

    /**
     * Tracks telemetry of replayed frames sent back via Hello Resume for diagnostic purposes.
     */
    fun recordHelloResumeReplayTelemetry(
        frame: ServerFrame,
        text: String,
        conversationIdOrNull: (ServerFrame) -> String?,
        seqOrNull: (ServerFrame) -> Long?,
    ) {
        if (helloResumeAfterSeqByConversation.isEmpty()) return
        val convId = conversationIdOrNull(frame)
            ?: runCatching { json.parseToJsonElement(text).jsonObject["conversation_id"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
            ?: return
        val afterSeq = helloResumeAfterSeqByConversation[convId] ?: return
        val seq = seqOrNull(frame)
            ?: runCatching {
                val obj = json.parseToJsonElement(text).jsonObject
                obj["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: obj["seq_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }.getOrNull()
            ?: return
        if (seq <= afterSeq) return
        val count = helloResumeReplayCountsByConversation.merge(convId, 1L) { old, inc -> old + inc } ?: 1L
        Telemetry.event(
            "ChannelTransport", "helloResume.replayedFrame",
            "conversationId" to convId,
            "afterSeq" to afterSeq,
            "seq" to seq,
            "replayedFrameCount" to count,
        )
    }

    /**
     * Resets the active cursor store and deletes the expired hello resume cursors on receiving a cursor_expired error.
     */
    fun clearExpiredCursor(frame: ServerFrame.Error, stateValueSimpleName: () -> String) {
        val cleared = mutableListOf<String>()
        val expiredConversationIds = linkedSetOf<String>()
        val conversationId = frame.conversationId
        val runId = frame.runId
        if (!conversationId.isNullOrEmpty() && !runId.isNullOrEmpty()) {
            cursorStore.clear(conversationId, runId)
            resumedRunConversationIds.remove(runId)
            cleared += "$conversationId/$runId"
            expiredConversationIds += conversationId
        } else if (!conversationId.isNullOrEmpty()) {
            cursorStore.activeRuns(conversationId).keys.forEach { activeRunId ->
                cursorStore.clear(conversationId, activeRunId)
                resumedRunConversationIds.remove(activeRunId)
                cleared += "$conversationId/$activeRunId"
            }
            expiredConversationIds += conversationId
        } else if (!runId.isNullOrEmpty()) {
            cursorStore.allActiveRuns().forEach { (activeConversationId, runs) ->
                if (runs.containsKey(runId)) {
                    cursorStore.clear(activeConversationId, runId)
                    resumedRunConversationIds.remove(runId)
                    cleared += "$activeConversationId/$runId"
                    expiredConversationIds += activeConversationId
                }
            }
        }
        if (expiredConversationIds.isNotEmpty()) {
            scope.launch {
                expiredConversationIds.forEach { expiredConversationId ->
                    runCatching { conversationCursorStore.clearCursor(expiredConversationId) }
                        .onFailure { t ->
                            Telemetry.error(
                                "ChannelTransport", "cursorExpired.clearConversationCursorFailed", t,
                                "conversationId" to expiredConversationId,
                            )
                        }
                }
            }
        }
        Log.w(
            TAG,
            "cursor_expired afterSeq=${frame.afterSeq} oldestSeq=${frame.oldestSeq} " +
                "lastSeq=${frame.lastSeq} cleared=${cleared.ifEmpty { listOf("<none>") }}",
        )
    }

    /**
     * Dispatches a recovery subscribe call for each currently active/persisted run.
     */
    fun resumeActiveRuns(
        subscribeFn: (String, Long) -> Boolean,
        stateValueSimpleName: () -> String,
    ) {
        val snapshot = cursorStore.allActiveRuns()
        if (snapshot.isEmpty()) return
        var dispatched = 0
        snapshot.forEach { (convId, runs) ->
            runs.forEach { (runId, lastSeq) ->
                resumedRunConversationIds[runId] = convId
                if (subscribeFn(runId, lastSeq)) {
                    dispatched++
                } else {
                    resumedRunConversationIds.remove(runId)
                    Log.w(
                        TAG,
                        "resume subscribe failed convId=$convId runId=$runId cursor=$lastSeq " +
                            "(state=${stateValueSimpleName()})",
                    )
                }
            }
        }
        if (dispatched > 0) {
            Log.i(TAG, "post-welcome resume scan dispatched $dispatched subscribe frame(s)")
        }
    }

    companion object {
        private const val TAG = "CursorResumeCoordinator"

        private val SKIP_CURSOR_TYPES: Set<String> = setOf(
            "welcome",
            "a2ui_capabilities",
            "user_action_ack",
            "user_action_outcome",
            "error",
            "cron_list_response",
            "cron_add_response",
            "cron_get_response",
            "cron_delete_response",
            "cron_delete_all_response",
            "crons_updated",
            "subscribe_frame",
            "subscribe_done",
        )
    }
}
