package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Marks the key of a row whose own key a live row or another settled row already holds. */
private const val SETTLED_KEY_SUFFIX = "~settled"

/**
 * The key, run id and turn latency each settled row was first drawn with, for one presentation's
 * lifetime.
 *
 * A row is remembered under its anchor, the identity of the record that owns it (a run's oldest
 * record). Once drawn, it keeps that presentation for as long as the chat is open: when the live
 * overlay drains, when a later generation regroups its page, and when a live frame for records it
 * already shows arrives after it (a reconnect catch-up drew the durable copy first). A row with the
 * same records always gets the same key, so a refresh that moves a page edge remounts nothing it
 * did not split or merge.
 *
 * The first time an anchor is drawn, the row takes over the live row it holds in full (its key,
 * run id and latency), or else keeps its own key. A key is never taken when another anchor already
 * holds it, which would list it twice, or when a live row under that key shows a record this row
 * does not hold, which the list would then show beside it under a second key. Such a row keys
 * `<own key>~settled`, which only its anchor can produce.
 *
 * A remembered key is kept even when a live row under it has since grown past the row (a page
 * edge split the row while its turn still streams): TimelineRowAssembly then lists that live row
 * beside it instead of hiding it.
 *
 * One entry per settled row drawn while the chat is open: bounded by the conversation's history.
 */
internal class SettledLiveAdoptions {
    private val byAnchor = HashMap<TimelineMessageId, LiveRowPresentation>()
    private val anchorByKey = HashMap<String, TimelineMessageId>()
    private val lock = SynchronizedObject()

    /** [item], the row [anchor] owns, as it is to be drawn: under the key it was first drawn with. */
    fun keyed(
        item: ChatRenderItem,
        anchor: TimelineMessageId,
        residents: List<TimelineResidentEvent>,
        live: () -> Pair<List<ChatRenderItem>, Map<String, TimelineMessageId>>,
    ): ChatRenderItem = synchronized(lock) { keyedLocked(item, anchor, residents, live()) }

    private fun keyedLocked(
        item: ChatRenderItem,
        anchor: TimelineMessageId,
        residents: List<TimelineResidentEvent>,
        live: Pair<List<ChatRenderItem>, Map<String, TimelineMessageId>>,
    ): ChatRenderItem {
        val (rows, aliases) = live
        byAnchor[anchor]?.let { return item.adopt(it) }
        val presentation = firstPresentation(item, residents, rows, aliases)
        byAnchor[anchor] = presentation
        anchorByKey[presentation.key] = anchor
        return item.adopt(presentation)
    }

    private fun firstPresentation(
        item: ChatRenderItem,
        residents: List<TimelineResidentEvent>,
        rows: List<ChatRenderItem>,
        aliases: Map<String, TimelineMessageId>,
    ): LiveRowPresentation {
        fun usable(presentation: LiveRowPresentation) =
            presentation.key !in anchorByKey && presentation.isOnlyHeldWithin(residents, rows, aliases)
        item.livePresentationFor(residents, rows, aliases)?.takeIf(::usable)?.let { return it }
        val own = LiveRowPresentation(item.key, runId = null, latencyMs = null)
        return if (usable(own)) own else own.copy(key = "${item.key}$SETTLED_KEY_SUFFIX")
    }
}
