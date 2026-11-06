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
    private val current = MutableStateFlow(read())
    val journal: StateFlow<PinJournal> = current.asStateFlow()

    /** Reconcile after the host explicitly clears all device preferences. */
    fun reload() = synchronized(lock) { current.value = read() }

    fun facets(ref: ScopedObjectRef): ObjectFacets {
        val index = current.value.pins.indexOf(ref)
        return ObjectFacets(pinned = index >= 0, pinOrder = index.takeIf { it >= 0 })
    }

    fun setPinned(ref: ScopedObjectRef, pinned: Boolean) = synchronized(lock) {
        val state = current.value
        val pins = when {
            !pinned -> state.pins - ref
            ref in state.pins -> state.pins
            else -> state.pins + ref
        }
        commit(state.copy(pins = pins, tombstones = if (pinned) state.tombstones - ref else state.tombstones + ref))
    }

    /** Reorder a visible subset in its existing slots, preserving all other sources and kinds. */
    fun reorder(refs: List<ScopedObjectRef>) = synchronized(lock) {
        val state = current.value
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
        val state = current.value
        if (state.imports.isNotEmpty() && sources.all { source ->
            state.imports.any { it.key == source.key && it.complete }
        }) return@synchronized
        val records = LegacyPinSources.keys.map { key ->
            val old = state.imports.firstOrNull { it.key == key } ?: PinImportRecord(key)
            val source = sources.firstOrNull { it.key == key }
            when {
                old.captured && old.error == null -> old
                source == null -> old
                else -> capture(source, old)
            }
        }
        commit(state.copy(imports = records))
        resolveImports(resolve)
    }

    /** Retries pending snapshots when their proven owner returns, without rereading completed keys. */
    fun resolvePending(resolve: (String, String) -> ScopedObjectRef?) = synchronized(lock) { resolveImports(resolve) }

    private fun resolveImports(resolve: (String, String) -> ScopedObjectRef?) {
        val state = current.value
        val records = state.imports.map { record ->
            if (!record.captured || record.error != null) record else record.copy(
                resolved = record.resolved + record.entries.mapNotNull { entry ->
                    (LegacyPinSources.shortcut(entry) ?: resolve(record.key, entry))?.let { entry to it }
                }.toMap(),
            )
        }
        val imported = records.flatMap { record -> record.entries.mapNotNull(record.resolved::get) }
        commit(state.copy(pins = (state.pins + imported).distinct().filterNot { it in state.tombstones }, imports = records))
        // commit reopens/verifies before a source can be marked complete. A crash here resumes union idempotently.
        commit(current.value.copy(imports = records.map { it.copy(complete = it.captured && it.error == null) }))
    }

    /** Seed only after both Home backings were read, and only if neither was explicit. */
    fun seedHomeDefaults(defaults: List<String>) = synchronized(lock) {
        val state = current.value
        if (state.homeDefaultsConsidered) return@synchronized
        val home = state.imports.filter { it.key in setOf(LegacyPinSources.HOME_PRODUCTION, LegacyPinSources.HOME_DEFAULT) }
        if (home.size != 2 || home.any { !it.complete }) return@synchronized
        val seeded = if (home.all { it.raw == null }) defaults.mapNotNull(LegacyPinSources::shortcut) else emptyList()
        commit(state.copy(pins = (state.pins + seeded).distinct().filterNot { it in state.tombstones }, homeDefaultsConsidered = true))
    }

    private fun capture(source: LegacyPinSource, previous: PinImportRecord): PinImportRecord {
        var raw: String? = previous.raw
        val result = runCatching {
            raw = source.read()
            PinImportRecord(source.key, raw, raw?.let(source.decode).orEmpty().distinct(), captured = true)
        }
        result.exceptionOrNull()?.let { error ->
            if (error is kotlinx.coroutines.CancellationException || error !is Exception) throw error
        }
        return result.getOrElse { error -> PinImportRecord(source.key, raw = raw, error = error::class.simpleName ?: "Read failed") }
    }

    private fun read(): PinJournal = backing.getString(STORAGE_KEY)?.let { LettaJson.wire.decodeFromString<PinJournal>(it) }
        ?.also { require(it.version == 1) { "Unsupported pin journal" } } ?: PinJournal()

    private fun commit(next: PinJournal) {
        backing.putDurableString(STORAGE_KEY, LettaJson.wire.encodeToString(next))
        val verified = read()
        check(verified == next) { "Pin journal verification failed" }
        current.value = verified
    }

    companion object {
        const val STORAGE_KEY = "core.objectPins.v1"
    }
}
