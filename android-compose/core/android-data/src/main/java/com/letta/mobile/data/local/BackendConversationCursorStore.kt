package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import com.letta.mobile.data.timeline.ConversationCursorStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "backend_conversation_cursors", primaryKeys = ["backendId", "conversationId"])
data class BackendConversationCursorEntity(
    val backendId: String,
    val conversationId: String,
    val highestSeenSeq: Long,
)

@Dao
interface BackendConversationCursorDao {
    @Query("INSERT INTO backend_conversation_cursors (backendId, conversationId, highestSeenSeq) VALUES (:backend, :conversation, :seq) ON CONFLICT(backendId, conversationId) DO UPDATE SET highestSeenSeq = MAX(highestSeenSeq, excluded.highestSeenSeq)")
    suspend fun record(backend: String, conversation: String, seq: Long)

    @Query("SELECT highestSeenSeq FROM backend_conversation_cursors WHERE backendId = :backend AND conversationId = :conversation")
    suspend fun get(backend: String, conversation: String): Long?

    @Query("SELECT * FROM backend_conversation_cursors WHERE backendId = :backend ORDER BY conversationId")
    suspend fun list(backend: String): List<BackendConversationCursorEntity>

    @Query("DELETE FROM backend_conversation_cursors WHERE backendId = :backend AND conversationId = :conversation")
    suspend fun clear(backend: String, conversation: String)

    @Query("UPDATE backend_conversation_cursors SET highestSeenSeq = :replacement WHERE backendId = :backend AND conversationId = :conversation AND highestSeenSeq = :expected")
    suspend fun replace(backend: String, conversation: String, expected: Long, replacement: Long): Int

    @Query("DELETE FROM backend_conversation_cursors WHERE backendId = :backend AND conversationId = :conversation AND highestSeenSeq = :expected")
    suspend fun clearExpected(backend: String, conversation: String, expected: Long): Int
}

/** Construct once per graph, not per screen. Never consults mutable settings or imports old rows. */
@Singleton
class BackendConversationCursorFactory @Inject constructor(private val database: LettaDatabase) {
    fun capture(backendId: String): CapturedBackendConversationCursorStore {
        require(backendId.isNotBlank())
        return CapturedBackendConversationCursorStore(backendId, database.backendConversationCursorDao())
    }
}

/**
 * Share this exact instance between transport replay and canonical committed repair. Runtime must
 * hold its graph admission/operation fence through the canonical commit and repair call. close()
 * drains cursor operations and permanently rejects late callbacks; it cannot undo a canonical commit.
 */
class CapturedBackendConversationCursorStore internal constructor(
    val backendId: String,
    private val dao: BackendConversationCursorDao,
) : ConversationCursorStore {
    private val operation = Mutex()
    private val open = java.util.concurrent.atomic.AtomicBoolean(true)

    private suspend fun <T> guarded(block: suspend () -> T): T = operation.withLock {
        check(open.get()) { "Captured cursor graph is closed" }
        block()
    }

    fun retire() { open.set(false) }

    suspend fun close() {
        retire()
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { operation.withLock { } }
    }

    override suspend fun recordFrame(conversationId: String, seq: Long) = guarded {
        require(seq >= 0)
        dao.record(backendId, conversationId, seq)
    }

    override suspend fun getCursor(conversationId: String): Long? = guarded { dao.get(backendId, conversationId) }

    override suspend fun getAllCursors(): Map<String, Long> = guarded {
        dao.list(backendId).associate { it.conversationId to it.highestSeenSeq }
    }

    override suspend fun clearCursor(conversationId: String) = guarded { dao.clear(backendId, conversationId) }

    /** Atomic CAS permits a lower replacement after expiry, but never overwrites a newer frame. */
    suspend fun replaceExpiredWatermark(conversationId: String, expected: Long, replacement: Long): Boolean = guarded {
        require(expected >= 0 && replacement >= 0)
        dao.replace(backendId, conversationId, expected, replacement) == 1
    }

    /**
     * Explicit CAS delete when a caller intentionally retires an expected watermark.
     * Captured cursor_expired handling must retain the row for commit-spanning replacement instead.
     */
    suspend fun clearExpiredWatermark(conversationId: String, expected: Long): Boolean = guarded {
        require(expected >= 0)
        dao.clearExpected(backendId, conversationId, expected) == 1
    }
}
