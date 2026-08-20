package com.letta.mobile.data.local

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.PendingLocalRecord
import com.letta.mobile.data.timeline.PendingLocalStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Room-backed [PendingLocalStore] (mge5.24). Persists optimistic user
 * messages with image attachments so they survive app restarts even when the
 * Letta server doesn't echo them back.
 *
 * Image bytes are stored inline as base64 inside a JSON column. Acceptable
 * for the few-image-per-conversation case; if this grows, switch to
 * file-system-backed blobs keyed by SHA256.
 */
@Singleton
class RoomPendingLocalStore @Inject constructor(
    private val dao: PendingLocalDao,
) : PendingLocalStore {

    override suspend fun save(record: PendingLocalRecord) {
        val payload = record.attachments.map { ImagePayload(base64 = it.base64, mediaType = it.mediaType) }
        dao.upsert(
            PendingLocalEntity(
                otid = record.otid,
                conversationId = record.conversationId,
                content = record.content,
                attachmentsJson = JSON.encodeToString(LIST_SER, payload),
                sentAtEpochMs = record.sentAt.toEpochMilli(),
            )
        )
    }

    override suspend fun delete(otid: String) = dao.deleteByOtid(otid)

    override suspend fun load(conversationId: String): List<PendingLocalRecord> {
        return dao.listForConversation(conversationId).map { row ->
            val images = runCatching { JSON.decodeFromString(LIST_SER, row.attachmentsJson) }
                .getOrDefault(emptyList())
                .map { MessageContentPart.Image(base64 = it.base64, mediaType = it.mediaType) }
            PendingLocalRecord(
                otid = row.otid,
                conversationId = row.conversationId,
                content = row.content,
                attachments = images,
                sentAt = Instant.ofEpochMilli(row.sentAtEpochMs),
            )
        }
    }

    @Serializable
    private data class ImagePayload(val base64: String, val mediaType: String)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val LIST_SER = ListSerializer(ImagePayload.serializer())
    }
}
