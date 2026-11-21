package com.letta.mobile.data.controller.node.iroh.feed

/**
 * The newest seq each conversation's journal issued before it left this host's memory or disk (trimmed from memory, or
 * deleted by the disk ceiling). A journal that is created again starts after that floor, so a seq is never issued twice in an
 * epoch: a viewer holding a cursor from before the eviction is told to resync (its cursor is behind the new horizon) and can
 * never silently skip new entries. A restart issues a new epoch, which resyncs every cursor anyway, so nothing is persisted.
 *
 * Bounded: past [capacity] conversations the oldest floor is folded into one shared floor, which only raises the starting
 * seq of conversations created afterwards (a larger seq is harmless; a reused one is not).
 */
internal class FeedSeqFloors(private val capacity: Int = DEFAULT_CAPACITY) {
    private val floors = LinkedHashMap<JournalKey, FeedSeq>()
    private var shared = NO_SEQ

    /** Records that [key]'s journal issued up to [head] before it was dropped. */
    fun note(key: JournalKey, head: FeedSeq) {
        val newest = maxOf(head, floors.remove(key) ?: NO_SEQ)
        floors[key] = newest
        if (floors.size > capacity) {
            val eldest = floors.entries.first()
            shared = maxOf(shared, eldest.value)
            floors.remove(eldest.key)
        }
    }

    /** The seq a new journal of [key] must start after. */
    fun floorFor(key: JournalKey): FeedSeq = maxOf(floors[key] ?: NO_SEQ, shared)

    private companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
