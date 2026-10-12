package com.letta.mobile.ui.host

import androidx.compose.runtime.Immutable

/**
 * The snackbars of an [OverlayHost]: the one [showing] and those [waiting], highest priority first
 * and then in order of arrival. Immutable: every change returns the next queue.
 */
@Immutable
internal data class SnackbarQueue(val showing: OverlayEntry? = null, val waiting: List<OverlayEntry> = emptyList()) {
    fun find(id: OverlayId): OverlayEntry? = showing?.takeIf { it.id == id } ?: waiting.firstOrNull { it.id == id }

    fun withKey(key: String): OverlayEntry? =
        showing?.takeIf { it.overlay.dedupeKey == key } ?: waiting.firstOrNull { it.overlay.dedupeKey == key }

    /** [overlay] in place of [id]'s; one on screen starts its time again at [now]. */
    fun replaced(id: OverlayId, overlay: Overlay, now: Long): SnackbarQueue =
        if (showing?.id == id) {
            copy(showing = showing.with(overlay, now))
        } else {
            copy(waiting = waiting.map { if (it.id == id) it.with(overlay) else it })
        }

    /** Adds [entry]: on screen when nothing is, in place of a lower-priority one (which waits), else waiting. */
    fun offered(entry: OverlayEntry): SnackbarQueue = when {
        showing == null -> copy(showing = entry)
        entry.overlay.priority > showing.overlay.priority -> SnackbarQueue(entry, listOf(showing) + waiting)
        else -> copy(waiting = waiting + entry)
    }

    /** Without [id]; when it was on screen the next waiting one shows, its time starting at [now]. */
    fun without(id: OverlayId, now: Long): SnackbarQueue {
        if (showing?.id != id) return copy(waiting = waiting.filterNot { it.id == id })
        val next = waiting.sortedByDescending { it.overlay.priority }.firstOrNull()
        return SnackbarQueue(next?.with(next.overlay, now), waiting.filterNot { it.id == next?.id })
    }
}

/** The banners of an [OverlayHost], all on screen, highest priority first. */
@Immutable
internal data class BannerStack(val entries: List<OverlayEntry> = emptyList()) {
    fun find(id: OverlayId): OverlayEntry? = entries.firstOrNull { it.id == id }

    fun withKey(key: String): OverlayEntry? = entries.firstOrNull { it.overlay.dedupeKey == key }

    /** [entry] added, or in place of the one with its id. */
    fun upserted(entry: OverlayEntry): BannerStack {
        val others = entries.filterNot { it.id == entry.id }
        return BannerStack((others + entry).sortedByDescending { it.overlay.priority })
    }

    fun without(id: OverlayId): BannerStack = BannerStack(entries.filterNot { it.id == id })
}
