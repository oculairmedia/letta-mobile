package com.letta.mobile.data.controller.extras

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * The live half of [ExternalToolRegistry] (letta-mobile-s416w.27): the [ToolSource]s added at
 * runtime, merged into one list on every read.
 *
 * Lock-free: both pieces of state are immutable values swapped atomically through
 * [MutableStateFlow.update], so adds, removes, reads and dispatch can race from any thread. The
 * sources' own [ToolSource.tools] are read at the moment of use, never cached.
 *
 * Names are unique across the merged set: a tool whose name a fixed tool (or an earlier source)
 * already has is dropped, so a plugin can never shadow a host tool.
 */
internal class DynamicToolSet(
    private val fixedNames: () -> Set<String>,
    private val isAdvertisable: (ExternalTool) -> Boolean,
) {
    /**
     * The sources, stamped with a revision so that an add and a remove the collector sees as one
     * conflated update still count as a change: a runtime started in between got the other set.
     */
    private val membership = MutableStateFlow(Membership(emptyList(), revision = 0))

    /** Every dynamic tool name ever sent to the App Server, so a late call can be told it was removed. */
    private val advertisedNames = MutableStateFlow<Set<String>>(emptySet())

    fun add(source: ToolSource): Boolean {
        var added = false
        membership.update { current ->
            added = current.sources.none { it.id == source.id }
            if (added) current.with(current.sources + source) else current
        }
        return added
    }

    fun remove(sourceId: String): Boolean {
        var removed = false
        membership.update { current ->
            val remaining = current.sources.filterNot { it.id == sourceId }
            removed = remaining.size != current.sources.size
            if (removed) current.with(remaining) else current
        }
        return removed
    }

    /** The sources' advertisable tools now, in source order, without names already taken. */
    fun current(): List<ExternalTool> {
        val snapshot = membership.value.sources
        if (snapshot.isEmpty()) return emptyList()
        val taken = fixedNames().toMutableSet()
        return snapshot.flatMap { it.tools.value }.filter { isAdvertisable(it) && taken.add(it.name) }
    }

    fun find(name: String): ExternalTool? = current().firstOrNull { it.name == name }

    /** Records which of [advertised] came from a source, as they are about to reach the server. */
    fun markAdvertised(advertised: List<ExternalTool>) {
        val fixed = fixedNames()
        val dynamic = advertised.mapNotNull { tool -> tool.name.takeUnless { it in fixed } }
        if (dynamic.isNotEmpty()) advertisedNames.update { it + dynamic }
    }

    fun wasAdvertised(name: String): Boolean = name in advertisedNames.value

    /**
     * The advertised set ([advertised] reads it) after every add or remove, and after a source
     * publishes a list whose definitions differ. The set current at collection is skipped.
     *
     * A source that publishes A, then B, then A again faster than the collector runs can be seen
     * as no change at all (StateFlow conflation); the next change re-sends the whole set.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changes(advertised: () -> List<ExternalTool>): Flow<List<ExternalTool>> =
        membership
            .flatMapLatest { current ->
                // distinctUntilChanged is per revision: the first set of each one always passes.
                current.contentChanges()
                    .map { advertised() }
                    .distinctUntilChanged { old, new -> old.definitions() == new.definitions() }
            }
            .drop(1)

    private data class Membership(val sources: List<ToolSource>, val revision: Long) {
        fun with(sources: List<ToolSource>) = Membership(sources, revision + 1)

        fun contentChanges(): Flow<Unit> =
            if (sources.isEmpty()) flowOf(Unit) else combine(sources.map { it.tools }) { }
    }

    private data class Definition(val name: String, val description: String, val schema: JsonObject?)

    private fun List<ExternalTool>.definitions(): List<Definition> =
        map { Definition(it.name, it.description, it.inputSchema) }
}
