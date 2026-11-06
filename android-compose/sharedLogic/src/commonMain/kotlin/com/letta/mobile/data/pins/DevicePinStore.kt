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
    private val lock = SynchronizedObject()
    private val initial = storageAttempt { read() }
    private val current = MutableStateFlow(initial.getOrDefault(PinJournal()))
    private val ready = MutableStateFlow(initial.isSuccess)
    private val readable = MutableStateFlow(initial.isSuccess)
    val journal: StateFlow<PinJournal> = current.asStateFlow()
    val availability: StateFlow<Boolean> = ready.asStateFlow()
    val hasReadableState: Boolean get() = readable.value

    /** Reconcile after an explicit reset or retry; failed reads never publish an empty journal. */
    fun reload(): Boolean = synchronized(lock) {
        storageAttempt { read() }.onSuccess(::publish).also { ready.value = it.isSuccess }.isSuccess
    }

    /** Hosts can open without a pin provider while a backing is temporarily unavailable. */
    fun tryImportLegacy(sources: List<LegacyPinSource>): Boolean = storageAttempt { importLegacy(sources) }.isSuccess

    private fun forWrite(): PinJournal = if (ready.value) current.value else read()

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
        commit(state.copy(pins = pins, tombstones = if (pinned) state.tombstones - ref else state.tombstones + ref))
    }

    /** Reorder a visible subset in its existing slots, preserving all other sources and kinds. */
    fun reorder(refs: List<ScopedObjectRef>) = synchronized(lock) {
        val state = forWrite()
        val ordered = refs.distinct().filter { it in state.pins }
        val positions = state.pins.indices.filter { state.pins[it] in ordered }
        val pins = state.pins.toMutableList()
        positions.zip(ordered).forEach { (index, ref) -> pins[index] = ref }
        commit(state.copy(pins = pins))
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
        val records = state.imports.map { record ->
            if (!record.captured || record.error != null) return@map record
            val resolved = record.entries.filterNot { it in record.resolved }.mapNotNull { entry ->
                (LegacyPinSources.shortcut(entry) ?: resolve(record.key, entry))?.let { entry to it }
            }.toMap()
            record.copy(resolved = record.resolved + resolved)
        }
        val checkpoints = records.any { it.key in completing && it.captured && it.error == null && !it.complete }
        if (records == state.imports && !checkpoints) return
        val imported = records.flatMap { record -> record.entries.mapNotNull(record.resolved::get) }
        commit(state.copy(pins = (state.pins + imported).distinct().filterNot { it in state.tombstones }, imports = records))
        // commit reopens/verifies before a source can be marked complete. A crash here resumes union idempotently.
        commit(current.value.copy(imports = records.map {
            it.copy(complete = it.complete || (it.key in completing && it.captured && it.error == null))
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

    private fun read(): PinJournal = backing.getString(STORAGE_KEY)?.let { LettaJson.wire.decodeFromString<PinJournal>(it) }
        ?.also { require(it.version == 1) { "Unsupported pin journal" } } ?: PinJournal()

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
