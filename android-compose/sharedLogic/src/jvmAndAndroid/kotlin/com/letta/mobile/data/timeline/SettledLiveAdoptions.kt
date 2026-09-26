package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem

/**
 * Which settled rows took over which live rows, for one presentation's lifetime.
 *
 * Remembered under every member identity, so the adoption survives the overlay draining and a run
 * that later grows an older row keeps it across paging generations. A live key is lent to exactly
 * one settled row: a second, unrelated row can never receive a key already on screen.
 */
internal class SettledLiveAdoptions {
    private val byIdentity = HashMap<TimelineMessageId, LiveRowPresentation>()
    private val holders = HashMap<String, Set<TimelineMessageId>>()

    /** [item] as the live row it replaces showed it, or null when it replaces none. */
    @Synchronized
    fun adopted(
        item: ChatRenderItem,
        residents: List<TimelineResidentEvent>,
        live: () -> Pair<List<ChatRenderItem>, Map<String, TimelineMessageId>>,
    ): ChatRenderItem? {
        val identities = residents.mapTo(mutableSetOf()) { it.identity }
        val presentation = identities.firstNotNullOfOrNull(byIdentity::get)
            ?: live().let { (rows, aliases) -> item.livePresentationFor(residents, rows, aliases) }
                ?.takeIf { holders[it.key].isNullOrEmpty() }
            ?: return null
        identities.forEach { byIdentity[it] = presentation }
        holders[presentation.key] = holders[presentation.key].orEmpty() + identities
        return item.adopt(presentation)
    }
}
