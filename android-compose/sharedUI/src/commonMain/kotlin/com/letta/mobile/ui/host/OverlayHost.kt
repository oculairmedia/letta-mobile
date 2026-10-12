package com.letta.mobile.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource

/**
 * letta-mobile-ckmub.3.2 / ckmub.3.15 (hard cut S13, primitive P10): the one queue for what floats
 * over a window without being a page — snackbars (with or without an undo) and banners.
 *
 * A surface never owns a SnackbarHostState. It calls [OverlayHost.show] on the host its window
 * root provides (see [OverlayHostRoot]); the host decides what is on screen:
 * - one snackbar at a time; the rest wait, highest [OverlayPriority] first, then in order of
 *   arrival. A higher-priority snackbar takes the place of a lower one, which waits to show again
 *   ([SnackbarQueue]);
 * - every banner at once, highest priority on top ([BannerStack]);
 * - an overlay whose key is already queued or showing replaces it in place (the same error twice
 *   shows once; a newer undo offer replaces the older one's bar);
 * - a timed overlay leaves after its [OverlayPresentation.durationMillis] on screen ([tick] with
 *   the host's clock).
 *
 * Leaving through its action calls [OverlayAction.onAction]; leaving any other way (timeout, the
 * dismiss control, [OverlayHandle.dismiss]) calls [OverlayResponse.onDismissed] — so an undo
 * commits on timeout. [OverlayHandle.withdraw] removes an overlay silently: its owner no longer
 * needs it.
 *
 * Inline errors are not queued: they belong to the page's layout
 * ([com.letta.mobile.ui.components.LettaInlineError]).
 */
@Stable
class OverlayHost(private val clock: () -> Long = monotonicMillis()) {
    private var snackbars by mutableStateOf(SnackbarQueue())
    private var bannerStack by mutableStateOf(BannerStack())
    private var nextId = 0L

    /** The snackbar on screen. */
    val snackbar: OverlayEntry? get() = snackbars.showing

    /** Snackbars waiting for the one on screen to leave, in the order they will show. */
    val waiting: List<OverlayEntry> get() = snackbars.waiting

    /** The banners on screen, highest priority first. */
    val banners: List<OverlayEntry> get() = bannerStack.entries

    /** Snackbar slots ([OverlaySnackbarSlot]) composed now; the newest draws the snackbar. */
    internal val slots = OverlaySlots()

    /** Queues [overlay] (or replaces the one with its key) and returns its handle. */
    fun show(overlay: Overlay): OverlayHandle {
        val now = clock()
        val id = when (overlay) {
            is Overlay.Snackbar -> showSnackbar(overlay, now)
            is Overlay.Banner -> showBanner(overlay, now)
        }
        return OverlayHandle(this, id)
    }

    /** Removes the overlay and calls its [OverlayResponse.onDismissed]. */
    fun dismiss(id: OverlayId) {
        remove(id)?.overlay?.response?.onDismissed?.invoke()
    }

    /** Removes the overlay and runs its action. */
    fun performAction(id: OverlayId) {
        remove(id)?.overlay?.action?.onAction?.invoke()
    }

    /** Removes the overlay without calling anything. */
    fun withdraw(id: OverlayId) {
        remove(id)
    }

    /** Whether the overlay [id] is showing or waiting. */
    fun isActive(id: OverlayId): Boolean = find(id) != null

    /** Dismisses every timed overlay whose time on screen is up. */
    fun tick() {
        val now = clock()
        onScreen().filter { it.expired(now) }.forEach { dismiss(it.id) }
    }

    /** Milliseconds until the next timed overlay is up, or null when nothing on screen is timed. */
    fun nextDeadlineIn(): Long? {
        val now = clock()
        return onScreen().mapNotNull { it.remaining(now) }.minOrNull()?.coerceAtLeast(0L)
    }

    private fun onScreen(): List<OverlayEntry> = listOfNotNull(snackbar) + banners

    private fun showSnackbar(overlay: Overlay.Snackbar, now: Long): OverlayId {
        val existing = snackbars.withKey(overlay.dedupeKey)
        if (existing != null) {
            snackbars = snackbars.replaced(existing.id, overlay, now)
            return existing.id
        }
        val entry = OverlayEntry(newId(), overlay, now)
        snackbars = snackbars.offered(entry)
        return entry.id
    }

    private fun showBanner(overlay: Overlay.Banner, now: Long): OverlayId {
        val id = bannerStack.withKey(overlay.dedupeKey)?.id ?: newId()
        bannerStack = bannerStack.upserted(OverlayEntry(id, overlay, now))
        return id
    }

    private fun remove(id: OverlayId): OverlayEntry? {
        val entry = find(id) ?: return null
        snackbars = snackbars.without(id, clock())
        bannerStack = bannerStack.without(id)
        return entry
    }

    private fun find(id: OverlayId): OverlayEntry? = snackbars.find(id) ?: bannerStack.find(id)

    private fun newId(): OverlayId = OverlayId(nextId++)
}

/** The composed [OverlaySnackbarSlot]s, in the order they arrived. */
@Stable
internal class OverlaySlots {
    private var nextId = 0L
    private var composed: List<Long> by mutableStateOf(emptyList())

    /** The slot that draws the snackbar (the newest), or null when the window layer draws it. */
    val active: Long? get() = composed.lastOrNull()

    fun newId(): Long = nextId++

    fun register(id: Long) {
        composed = composed + id
    }

    fun unregister(id: Long) {
        composed = composed - id
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
