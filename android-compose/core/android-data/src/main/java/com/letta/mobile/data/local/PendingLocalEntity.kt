package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted optimistic user message that we MUST keep across app restarts.
 *
 * Why this exists (mge5.24, 2026-04-19):
 * The Letta server does NOT persist user messages whose content includes
 * non-text parts (images). The agent receives + processes the image, replies
 * with assistant/tool frames that DO get stored, but the user's outbound
 * message is dropped from the conversation log. Without local persistence,
 * the user's image bubble survives only in the in-memory [TimelineSyncLoop]
 * state — kill the process, lose the image.
 *
 * On send with attachments we write a row here. On hydrate we re-inject any
 * row whose otid isn't echoed by the server. On reconcile, if the server DID
 * echo our otid back (e.g. the server got fixed, or it was actually a text
 * send queued through the same path), we delete the row.
 *
 * Storage shape:
 * - [attachmentsJson]: JSON array of `{base64, mediaType}` objects, one per
 *   attached image. We store the same bytes Coil renders so the chat bubble
 *   can be reconstructed offline. Keep an eye on row size — large images
 *   inflate the DB. If this grows problematic we'll switch to file storage
 *   keyed by hash.
 * - [backendId] / [agentId] (schema v20, letta-mobile-by4vm.17): the owning
 *   backend and agent. `conv-default-*` ids are shared by every agent and every
 *   backend, so the conversation id alone is not an owner. Null means the writer
 *   could not know it — every row migrated from v19 has both null. Reads treat a
 *   null as "legacy, matches any"; [PendingLocalDao.deleteForBackend] purges legacy
 *   rows on the next backend switch, so the leniency spans one backend session.
 */
@Entity(
    tableName = "pending_local_messages",
    indices = [Index(value = ["backendId", "agentId", "conversationId"])],
)
data class PendingLocalEntity(
    @PrimaryKey val otid: String,
    val conversationId: String,
    val content: String,
    val attachmentsJson: String,
    val sentAtEpochMs: Long,
    val deliveryState: String,
    val backendId: String? = null,
    val agentId: String? = null,
)
