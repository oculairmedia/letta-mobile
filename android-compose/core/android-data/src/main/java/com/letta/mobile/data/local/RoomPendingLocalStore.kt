package com.letta.mobile.data.local

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.timeline.PendingLocalRecord
import com.letta.mobile.data.timeline.PendingLocalScope
import com.letta.mobile.data.timeline.PendingLocalStore
import java.time.Instant
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
 *
 * letta-mobile-by4vm.17: rows are owned by (backendId, agentId, conversationId) and
 * this store is a [BackendScopedCache]. [clearForBackendSwitch] runs BEFORE the active
 * backend changes, so [activeBackendId] still names the outgoing backend: its rows and
 * every legacy unknown-backend row are dropped, matching the confirmed-snapshot store.
 * Constructed by the app's DatabaseModule, which supplies [activeBackendId].
 */
class RoomPendingLocalStore(
    private val dao: PendingLocalDao,
    private val activeBackendId: () -> String,
) : PendingLocalStore, BackendScopedCache {

    override suspend fun save(record: PendingLocalRecord) {
        val payload = record.attachments.map { ImagePayload(base64 = it.base64, mediaType = it.mediaType) }
        dao.upsert(
            PendingLocalEntity(
                otid = record.otid,
                conversationId = record.conversationId,
                content = record.content,
                attachmentsJson = JSON.encodeToString(LIST_SER, payload),
                sentAtEpochMs = record.sentAt.toEpochMilli(),
                deliveryState = record.deliveryState.name,
                backendId = record.backendId,
                agentId = record.agentId,
            )
        )
    }

    override suspend fun delete(otid: String) = dao.deleteByOtid(otid)

    override suspend fun markFailed(otid: String) {
        dao.markFailedAndKeepNewest(otid)
    }

    override suspend fun clearForBackendSwitch() {
        dao.deleteForBackend(activeBackendId())
    }

    override suspend fun load(scope: PendingLocalScope): List<PendingLocalRecord> {
        return dao.listForScope(scope.backendId, scope.agentId, scope.conversationId).map { row ->
            val images = runCatching { JSON.decodeFromString(LIST_SER, row.attachmentsJson) }
                .getOrDefault(emptyList())
                .map { MessageContentPart.Image(base64 = it.base64, mediaType = it.mediaType) }
            PendingLocalRecord(
                otid = row.otid,
                conversationId = row.conversationId,
                content = row.content,
                attachments = images,
                sentAt = Instant.ofEpochMilli(row.sentAtEpochMs),
                deliveryState = runCatching { com.letta.mobile.data.timeline.DeliveryState.valueOf(row.deliveryState) }
                    .getOrDefault(com.letta.mobile.data.timeline.DeliveryState.SENT),
                backendId = row.backendId,
                agentId = row.agentId,
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
