package com.letta.mobile.data.chat.projection

/**
 * The live overlay and the settled pages as the one index space a timeline list renders, built
 * from a single read of both sources.
 *
 * A settled row takes over the key of the live row it replaces, and the live
 * row only drains once the presentation has seen the settled one resident, so for at least a frame
 * both sources hold the key. Hiding the live copy is what lets the settled row replace it in place.
 * That hiding must read the same settled snapshot the list keys are read from: filtering the live
 * rows at composition while the lazy list read its keys at measure let a page that landed between
 * the two (Paging presents on the UI dispatcher, which can run mid-frame) put the key on screen
 * twice, and the guard dropped one copy on every settled turn.
 *
 * A settled row stands in for a live row only when it holds every record of it. A turn that is
 * durable only in part (a mid-turn catch-up, or a page edge that cut a run while it streams) can
 * leave a live row under a key a settled part already holds; hiding it would take the records that
 * are not durable yet off the screen. The live row keeps its key (it is the one still streaming, and
 * renaming it remounted it: its text parsed again mid-stream), and the settled part is listed as
 * `<key>~part` until the rest of it is resident, when the whole settled row takes the key over.
 */
class TimelineRowAssembly private constructor(
    /** Live rows still shown: those whose settled twin is not yet resident. */
    val live: List<ChatRenderItem>,
    private val settledKeys: List<String?>,
    /** Rows [TimelineRowKeyGuard] had to blank; empty unless an upstream projection is broken. */
    val duplicates: Map<Int, String>,
) {
    val liveCount: Int get() = live.size
    val size: Int get() = live.size + settledKeys.size

    /** The key row [index] is listed under, or null for a settled placeholder. */
    fun key(index: Int): String? =
        duplicates[index] ?: live.getOrNull(index)?.key ?: settledKeys.getOrNull(index - live.size)

    companion object {
        const val LIVE = "live"
        const val SETTLED = "settled"

        /** Suffix of a settled row listed beside the live row it holds only part of. */
        const val PARTLY_SETTLED_SUFFIX = "~part"

        /** Assembles [live] ahead of the presented [settled] slots, as one read of both. */
        fun assemble(live: List<ChatRenderItem>, settled: Settled): TimelineRowAssembly {
            val overlay = LiveOverlay.of(live, settled)
            val settledKeys = if (overlay.partlySettled.isEmpty()) {
                settled.keys
            } else {
                settled.keys.mapIndexed { slot, key -> if (slot in overlay.partlySettled) key + PARTLY_SETTLED_SUFFIX else key }
            }
            val duplicates = TimelineRowKeyGuard.duplicateRows(overlay.shown.map { it.key } + settledKeys) { index ->
                if (index < overlay.shown.size) LIVE else SETTLED
            }
            return TimelineRowAssembly(overlay.shown, settledKeys, duplicates)
        }

        /**
         * The live rows no resident settled row has taken over yet: a live row whose key a settled
         * row holds is hidden when that row holds all of it ([Settled.holds]), and keeps its key when
         * not (the settled part is then listed as `<key>~part`).
         */
        fun displayedLive(live: List<ChatRenderItem>, settled: Settled): List<ChatRenderItem> = LiveOverlay.of(live, settled).shown
    }

    /** The live rows still shown, and the settled slots holding only part of a live row under its key. */
    private class LiveOverlay(val shown: List<ChatRenderItem>, val partlySettled: Set<Int>) {
        companion object {
            fun of(live: List<ChatRenderItem>, settled: Settled): LiveOverlay {
                if (live.isEmpty() || settled.keys.isEmpty()) return LiveOverlay(live, emptySet())
                val slotByKey = HashMap<String, Int>(settled.keys.size)
                settled.keys.forEachIndexed { index, key -> if (key != null) slotByKey.getOrPut(key) { index } }
                val partly = HashSet<Int>()
                val shown = live.filter { item ->
                    val slot = slotByKey[item.key] ?: return@filter true
                    val held = settled.holds(slot, item)
                    if (!held) partly += slot
                    !held
                }
                return LiveOverlay(shown, partly)
            }
        }
    }

    /**
     * The presented settled slots of one read, as the list keys them.
     *
     * @property keys one entry per slot, null for a placeholder.
     * @property holds whether the settled row in a slot (by index) holds every record of a live row
     *   listed under its key; without it a key match is taken to mean it does.
     */
    class Settled(
        val keys: List<String?>,
        val holds: (Int, ChatRenderItem) -> Boolean = { _, _ -> true },
    )
}
