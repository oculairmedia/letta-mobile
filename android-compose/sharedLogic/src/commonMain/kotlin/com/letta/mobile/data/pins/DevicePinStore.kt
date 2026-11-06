package com.letta.mobile.data.pins

import com.letta.mobile.data.objectverbs.ObjectFacets
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The sole pin writer. Serialized, verified journal commits publish confirmed state only. Legacy
 * backings are never removed. Snapshots precede import; union and checkpoints survive interruption.
 */
class DevicePinStore(private val backing: SecureSettingsStore) {
    private val current: MutableStateFlow<PinJournal>
    private val ready: MutableStateFlow<Boolean>
    private val readable: MutableStateFlow<Boolean>

    init {
        val initial = storageAttempt { read() }
        current = MutableStateFlow(initial.getOrDefault(PinJournal(legacyOrderedPins = emptyList())))
        ready = MutableStateFlow(initial.isSuccess)
        readable = MutableStateFlow(initial.isSuccess)
    }
    val journal: StateFlow<PinJournal> = current.asStateFlow()
    val availability: StateFlow<Boolean> = ready.asStateFlow()
    val hasReadableState: Boolean get() = readable.value

    /** Reconcile after an explicit reset or retry; failed reads never publish an empty journal. */
    fun reload(): Boolean = synchronized(lock) {
        storageAttempt { read() }.onSuccess(::publish).also { ready.value = it.isSuccess }.isSuccess
    }

    /** Hosts can open without a pin provider while a backing is temporarily unavailable. */
    fun tryImportLegacy(sources: List<LegacyPinSource>): Boolean = storageAttempt { importLegacy(sources) }.isSuccess

    // Always reread under the process-local lock so another store instance cannot leave us stale.
    private fun forWrite(): PinJournal = storageAttempt { read() }.getOrElse {
        ready.value = false
        throw it
    }

    fun facets(ref: ScopedObjectRef): ObjectFacets {
        val index = current.value.pins.indexOf(ref)
        return ObjectFacets(pinned = index >= 0, pinOrder = index.takeIf { it >= 0 })
    }

    fun setPinned(ref: ScopedObjectRef, pinned: Boolean) = synchronized(lock) {
        val state = forWrite()
        val pins = when {
            !pinned -> state.pins - ref
            ref in state.pins -> state.pins
            else -> state.pins + ref
        }
        commit(state.copy(pins = pins, tombstones = if (pinned) state.tombstones - ref else state.tombstones + ref,
            legacyOrderedPins = if ((ref in state.pins) == pinned) state.legacyOrderedPins else state.legacyOrderedPins.orEmpty() - ref))
    }

    /** Reorder a visible subset in its existing slots, preserving all other sources and kinds. */
    fun reorder(refs: List<ScopedObjectRef>) = synchronized(lock) {
        val state = forWrite()
        val ordered = refs.distinct().filter { it in state.pins }
        val positions = state.pins.indices.filter { state.pins[it] in ordered }
        val pins = state.pins.toMutableList()
        positions.zip(ordered).forEach { (index, ref) -> pins[index] = ref }
        commit(state.copy(pins = pins, legacyOrderedPins = if (pins == state.pins) state.legacyOrderedPins else emptyList()))
    }

    /**
     * Capture every available backing before union. Failed reads/decodes remain incomplete. Resolver
     * receives snapshot key + opaque entry and may return only an evidenced owner, never active backend.
     */
    fun importLegacy(
        sources: List<LegacyPinSource>,
        resolve: (String, String) -> ScopedObjectRef? = { _, entry -> LegacyPinSources.shortcut(entry) },
    ) = synchronized(lock) {
        val state = forWrite()
        if (state.imports.isNotEmpty() && sources.all { source ->
            state.imports.any { it.key == source.key && it.complete }
        }) {
            publish(state)
            resolveImports(resolve)
            return@synchronized
        }
        val records = LegacyPinSources.keys.map { key ->
            val old = state.imports.firstOrNull { it.key == key } ?: PinImportRecord(key)
            val source = sources.firstOrNull { it.key == key }
            when {
                old.complete -> old
                source == null -> old
                else -> capture(source, old)
            }
        }
        commit(state.copy(imports = records))
        resolveImports(resolve, sources.mapTo(mutableSetOf()) { it.key })
    }

    /** Retries pending snapshots when their proven owner returns, without rereading completed keys. */
    fun resolvePending(resolve: (String, String) -> ScopedObjectRef?) = synchronized(lock) { resolveImports(resolve) }

    private fun resolveImports(resolve: (String, String) -> ScopedObjectRef?, completing: Set<String> = emptySet()) {
        val state = forWrite()
        val records = state.imports.map { it.resolveWith(resolve) }
        val checkpoints = records.any { it.canComplete(completing) }
        if (records == state.imports && !checkpoints) return
        val imported = records.flatMap { record -> record.entries.mapNotNull(record.resolved::get) }
        // Refill only migration slots; user-added pins retain their exact positions.
        val legacy = state.legacyOrderedPins.orEmpty()
        val canonical = state.pins.filterNot { it in legacy }
        val migrated = (imported + legacy).distinct().filterNot { it in canonical || it in state.tombstones }
        val remaining = migrated.iterator()
        val pins = state.pins.mapNotNull { pin ->
            if (pin in legacy) if (remaining.hasNext()) remaining.next() else null else pin
        } + remaining.asSequence().toList()
        commit(state.copy(pins = pins, imports = records, legacyOrderedPins = migrated))
        // commit reopens/verifies before a source can be marked complete. A crash here resumes union idempotently.
        commit(current.value.copy(imports = records.map {
            it.copy(complete = it.complete || it.canComplete(completing))
        }))
    }

    /** Seed only after both Home backings were read, and only if neither was explicit. */
    fun seedHomeDefaults(defaults: List<String>): Boolean = storageAttempt { synchronized(lock) {
        val state = forWrite()
        if (state.homeDefaultsConsidered) return@synchronized
        val home = state.imports.filter { it.key in setOf(LegacyPinSources.HOME_PRODUCTION, LegacyPinSources.HOME_DEFAULT) }
        if (home.size != 2 || home.any { !it.complete }) return@synchronized
        val seeded = if (home.all { it.raw == null }) defaults.mapNotNull(LegacyPinSources::shortcut) else emptyList()
        commit(state.copy(pins = (state.pins + seeded).distinct().filterNot { it in state.tombstones }, homeDefaultsConsidered = true))
    } }.isSuccess

    private fun capture(source: LegacyPinSource, previous: PinImportRecord): PinImportRecord {
        var raw: String? = null
        var readCompleted = false
        val result = storageAttempt {
            raw = source.read()
            readCompleted = true
            PinImportSnapshot(raw, raw?.let(source.decode).orEmpty().distinct())
        }
        val snapshot = result.getOrElse { error -> PinImportSnapshot(raw, error = error::class.simpleName ?: "Read failed") }
        val history = previous.snapshots.ifEmpty {
            if (previous.captured || previous.error != null) {
                listOf(PinImportSnapshot(previous.raw, previous.entries, previous.error))
            } else emptyList()
        }
        return previous.copy(
            raw = if (readCompleted) raw else previous.raw,
            entries = (snapshot.entries + previous.entries).distinct(),
            error = snapshot.error,
            captured = result.isSuccess,
            complete = false,
            snapshots = (history + snapshot).distinct(),
        )
    }

    private fun read(): PinJournal {
        val state = backing.getString(STORAGE_KEY)?.let { LettaJson.wire.decodeFromString<PinJournal>(it) }
            ?: PinJournal(legacyOrderedPins = emptyList())
        require(state.version == 1) { "Unsupported pin journal" }
        if (state.legacyOrderedPins != null) return state
        // Older journals omitted provenance. Recover it only when the saved order agrees with
        // the recorded imports; a different order may have been explicitly edited by the user.
        val imported = state.imports.flatMap { record -> record.entries.mapNotNull(record.resolved::get) }.distinct()
        val candidates = state.pins.filter { it in imported }
        val recordedOrder = imported.filter { it in candidates }
        return state.copy(legacyOrderedPins = if (candidates == recordedOrder) candidates else emptyList())
    }

    private fun commit(next: PinJournal) {
        val result = storageAttempt {
            backing.putDurableString(STORAGE_KEY, LettaJson.wire.encodeToString(next))
            val verified = read()
            check(verified == next) { "Pin journal verification failed" }
            publish(verified)
        }
        result.exceptionOrNull()?.let { error ->
            ready.value = false
            // A failed acknowledgement is not permission to replace the last confirmed state.
            storageAttempt { read() }
            throw error
        }
    }

    private fun publish(state: PinJournal) {
        current.value = state
        readable.value = true
        ready.value = true
    }

    companion object {
        // This lock coordinates store instances in one runtime only. Cross-process/tab writes need
        // host-provided locking or atomic compare-and-set; readback cannot supply that guarantee.
        private val lock = SynchronizedObject()
        const val STORAGE_KEY = "core.objectPins.v1"
    }
}

private inline fun <T> storageAttempt(block: () -> T): Result<T> {
    val result = runCatching(block)
    result.exceptionOrNull()?.let { error ->
        if (error is kotlinx.coroutines.CancellationException || error !is Exception) throw error
    }
    return result
}

private fun PinImportRecord.resolveWith(resolve: (String, String) -> ScopedObjectRef?): PinImportRecord {
    if (!captured || error != null) return this
    val newlyResolved = entries.filterNot { it in resolved }.mapNotNull { entry ->
        (LegacyPinSources.shortcut(entry) ?: resolve(key, entry))?.let { entry to it }
    }.toMap()
    return copy(resolved = resolved + newlyResolved)
}

private fun PinImportRecord.canComplete(available: Set<String>): Boolean = when {
    complete -> false
    !captured -> false
    error != null -> false
    else -> key in available
}
