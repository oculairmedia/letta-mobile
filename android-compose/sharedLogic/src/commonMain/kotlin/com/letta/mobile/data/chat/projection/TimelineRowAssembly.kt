package com.letta.mobile.data.chat.projection

/**
 * The live overlay and the settled pages as the one index space a timeline list renders, built
 * from a single read of both sources (letta-mobile-qygvv.24).
 *
 * A settled row takes over the key of the live row it replaces (letta-mobile-sibr8), and the live
 * row only drains once the presentation has seen the settled one resident, so for at least a frame
 * both sources hold the key. Hiding the live copy is what lets the settled row replace it in place.
 * That hiding must read the same settled snapshot the list keys are read from: filtering the live
 * rows at composition while the lazy list read its keys at measure let a page that landed between
 * the two (Paging presents on the UI dispatcher, which can run mid-frame) put the key on screen
 * twice, and the guard dropped one copy on every settled turn.
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

        /** [settledKeys] holds one entry per settled slot, null for a placeholder. */
        fun assemble(live: List<ChatRenderItem>, settledKeys: List<String?>): TimelineRowAssembly {
            val shown = displayedLive(live, settledKeys)
            val duplicates = TimelineRowKeyGuard.duplicateRows(shown.map { it.key } + settledKeys) { index ->
                if (index < shown.size) LIVE else SETTLED
            }
            return TimelineRowAssembly(shown, settledKeys, duplicates)
        }

        /** The live rows whose key no resident settled row has taken over yet. */
        fun displayedLive(live: List<ChatRenderItem>, settledKeys: Collection<String?>): List<ChatRenderItem> {
            if (live.isEmpty() || settledKeys.isEmpty()) return live
            val taken = settledKeys.filterNotNullTo(HashSet(settledKeys.size))
            return live.filterNot { it.key in taken }
        }
    }
}
