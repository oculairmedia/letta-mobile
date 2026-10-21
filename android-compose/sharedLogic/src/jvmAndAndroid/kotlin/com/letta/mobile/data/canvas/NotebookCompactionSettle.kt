package com.letta.mobile.data.canvas

import com.letta.mobile.data.storage.sha256Hex
import org.automerge.Document
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

private const val SNAPSHOT = "snapshot"
private val CHUNK_KINDS = setOf(SNAPSHOT, "incremental")

/** Whether [key] is a compaction: the repository writes a snapshot only to compact a document. */
internal fun isCompactionSnapshot(key: StorageKey): Boolean = key.parts.getOrNull(1) == SNAPSHOT

/**
 * Finish a compaction of the document stored as [name], once the repository has closed, so what
 * a closed store leaves on disk does not depend on when it closed.
 *
 * The repository (samod-core 0.9) compacts a document by writing one snapshot of it, then deletes
 * the chunks that snapshot supersedes on its next save. A snapshot acknowledged while the
 * repository is stopping gets no next save: the superseded chunks stay until the document is next
 * opened (one probe left 122 chunks, 249 KB, of a 1.3 KB document). Their changes are all in the
 * snapshot, so nothing was lost, only space.
 *
 * A document still in more than one chunk is rewritten as a single snapshot of all its chunks'
 * changes, its whole history, and the rest are deleted. The snapshot is written before anything
 * is deleted, so a crash between leaves extra chunks, never missing ones.
 */
internal fun settleCompaction(storage: Storage, name: String) {
    val chunks = storage.loadRange(StorageKey(name)).get().filterKeys { it.parts.getOrNull(1) in CHUNK_KINDS }
    if (chunks.size <= 1) return
    val merged = Document()
    try {
        chunks.values.forEach(merged::applyEncodedChanges)
        // Named as the repository names a compaction: the SHA-256 of the document's heads.
        val snapshot = StorageKey(name, SNAPSHOT, sha256Hex(merged.heads.fold(ByteArray(0)) { bytes, head -> bytes + head.bytes }))
        if (snapshot !in chunks) storage.put(snapshot, merged.save()).get()
        (chunks.keys - snapshot).forEach { storage.delete(it).get() }
    } finally {
        merged.free()
    }
}
