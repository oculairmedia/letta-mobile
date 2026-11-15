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
 * are not durable yet off the screen, and renaming the settled part would remount a row that did
 * not change. That live row stays listed as `<key>~live` until the rest of it is resident.
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

        /** Suffix of a live row listed beside a settled row that holds only part of it. */
        const val PARTLY_SETTLED_SUFFIX = "~live"

        /**
         * [settledKeys] holds one entry per settled slot, null for a placeholder. [holds] tells
         * whether the settled row in a slot holds every record of a live row under its key; without
         * it a key match is taken to mean it does.
         */
        fun assemble(
            live: List<ChatRenderItem>,
            settledKeys: List<String?>,
            holds: (settledIndex: Int, live: ChatRenderItem) -> Boolean = { _, _ -> true },
        ): TimelineRowAssembly {
            val shown = displayedLive(live, settledKeys, holds)
            val duplicates = TimelineRowKeyGuard.duplicateRows(shown.map { it.key } + settledKeys) { index ->
                if (index < shown.size) LIVE else SETTLED
            }
            return TimelineRowAssembly(shown, settledKeys, duplicates)
        }

        /**
         * The live rows no resident settled row has taken over yet: a live row whose key a settled
         * row holds is hidden when that row [holds] all of it, and listed as `<key>~live` when not.
         */
        fun displayedLive(
            live: List<ChatRenderItem>,
            settledKeys: List<String?>,
            holds: (settledIndex: Int, live: ChatRenderItem) -> Boolean = { _, _ -> true },
        ): List<ChatRenderItem> {
            if (live.isEmpty() || settledKeys.isEmpty()) return live
            val slotByKey = HashMap<String, Int>(settledKeys.size)
            settledKeys.forEachIndexed { index, key -> if (key != null) slotByKey.getOrPut(key) { index } }
            return live.mapNotNull { item ->
                val slot = slotByKey[item.key] ?: return@mapNotNull item
                if (holds(slot, item)) null else item.withKey(item.key + PARTLY_SETTLED_SUFFIX)
            }
        }

        private fun ChatRenderItem.withKey(key: String): ChatRenderItem = when (this) {
            is ChatRenderItem.Single -> copy(keyOverride = key)
            is ChatRenderItem.RunBlock -> copy(keyOverride = key)
        }
    }
}
