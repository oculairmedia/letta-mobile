package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Which settled rows took over which live rows, for one presentation's lifetime.
 *
 * An adopted key is remembered under ONE anchor, the identity of the row that adopted it. Only a
 * row holding that record finds the key again, so the adoption survives the overlay draining and a
 * run that later grows an older row keeps it across paging generations, while a page edge that
 * later cuts the run gives the key to the part holding the anchor and lets every other part key by
 * its own records. Remembering it under every member let each part of a cut run find it, and the
 * list held one live key twice. A live key is lent at most once: a second, unrelated row can never
 * receive a key already on screen.
 */
internal class SettledLiveAdoptions {
    private val byAnchor = HashMap<TimelineMessageId, LiveRowPresentation>()
    private val lent = HashSet<String>()
    private val lock = SynchronizedObject()

    /** [item], the row [anchor] owns, as the live row it replaces showed it, or null when it replaces none. */
    fun adopted(
        item: ChatRenderItem,
        anchor: TimelineMessageId,
        residents: List<TimelineResidentEvent>,
        live: () -> Pair<List<ChatRenderItem>, Map<String, TimelineMessageId>>,
    ): ChatRenderItem? = synchronized(lock) { adoptLocked(item, anchor, residents, live) }

    private fun adoptLocked(
        item: ChatRenderItem,
        anchor: TimelineMessageId,
        residents: List<TimelineResidentEvent>,
        live: () -> Pair<List<ChatRenderItem>, Map<String, TimelineMessageId>>,
    ): ChatRenderItem? {
        val (rows, aliases) = live()
        residents.firstNotNullOfOrNull { byAnchor[it.identity] }?.let { remembered ->
            // The live row under that key may have grown past this row (a later page edge moved
            // into the run while it still streams); taking the key would hide those records.
            return if (remembered.isOnlyHeldWithin(residents, rows, aliases)) item.adopt(remembered) else null
        }
        val presentation = item.livePresentationFor(residents, rows, aliases)
            ?.takeIf { it.key !in lent }
            ?: return null
        byAnchor[anchor] = presentation
        lent += presentation.key
        return item.adopt(presentation)
    }
}
