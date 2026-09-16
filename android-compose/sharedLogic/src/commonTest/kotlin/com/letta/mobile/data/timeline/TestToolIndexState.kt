package com.letta.mobile.data.timeline

/** Copy-on-transaction tool state used by scoped storage fakes. */
internal class TestToolIndexState(
    val entries: MutableMap<String, TimelineToolIndexEntry> = mutableMapOf(),
    var generation: Long = 0,
) {
    fun snapshot() = TestToolIndexState(entries.toMutableMap(), generation)
    fun unresolved(after: String?, limit: Int): List<TimelineToolIndexEntry> {
        require(limit > 0)
        return entries.values.asSequence()
            .filter { it.owner != null && !it.returned && (after == null || it.callId > after) }
            .sortedBy { it.callId }.take(limit).toList()
    }
    fun put(entry: TimelineToolIndexEntry) {
        require(entry.callId.isNotBlank())
        entries[entry.callId] = entry
    }
    fun advance(next: Long) { require(next > generation); generation = next }
}
