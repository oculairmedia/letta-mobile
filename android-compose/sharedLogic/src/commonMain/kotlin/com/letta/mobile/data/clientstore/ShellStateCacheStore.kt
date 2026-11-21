package com.letta.mobile.data.clientstore

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateSchema
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.ShellStateSnapshotCache
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * The disposable mirror of the host's shell state document ([ClientStoreEntries.ShellStateMirror]), kept in the
 * platform's small key-value store. It holds only entries in the namespaces the host allows
 * ([ShellStateWire.KEY_NAMESPACES]), whatever a host sent, so nothing outside a moved area (and no secret) is ever
 * written to the device. A value that cannot be read, or that is of a newer schema than this build reads, is no
 * mirror; no call here throws.
 */
class ShellStateCacheStore(private val store: SecureSettingsStore) : ShellStateSnapshotCache, DisposableCache {
    override val entry: ClientStoreEntry = ClientStoreEntries.ShellStateMirror

    override fun load(): ShellStateSnapshot? = runCatching {
        store.getString(entry.storageKey)?.let { LettaJson.persisted.decodeFromString(ShellStateSnapshot.serializer(), it) }
    }.getOrNull()?.takeIf { it.schemaVersion <= ShellStateSchema.VERSION }?.allowedOnly()

    override fun save(snapshot: ShellStateSnapshot) {
        runCatching {
            store.putString(entry.storageKey, LettaJson.persisted.encodeToString(ShellStateSnapshot.serializer(), snapshot.allowedOnly()))
        }
    }

    override fun clear() {
        runCatching { store.remove(entry.storageKey) }
    }

    override suspend fun clearAll() = clear()

    private fun ShellStateSnapshot.allowedOnly(): ShellStateSnapshot =
        copy(entries = entries.filterKeys(ShellStateWire::isAllowedKey))
}
