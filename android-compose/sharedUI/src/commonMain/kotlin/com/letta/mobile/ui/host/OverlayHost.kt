package com.letta.mobile.ui.host

import androidx.compose.runtime.Immutable
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
 *   arrival. A higher-priority snackbar takes the place of a lower one, which waits to show again;
 * - every banner at once, highest priority on top;
 * - an overlay whose [Overlay.dedupeKey] is already queued or showing replaces it in place (the
 *   same error twice shows once; a newer undo offer replaces the older one's bar);
 * - a timed overlay leaves after [Overlay.durationMillis] on screen ([tick] with the host's clock).
 *
 * Leaving through its action calls [OverlayAction.onAction]; leaving any other way (timeout, the
 * dismiss control, [OverlayHandle.dismiss]) calls [Overlay.onDismissed] — so an undo commits on
 * timeout. [OverlayHandle.withdraw] removes an overlay silently: its owner no longer needs it.
 *
 * Inline errors are not queued: they belong to the page's layout ([LettaInlineError]).
 */
@Stable
class OverlayHost(private val clock: () -> Long = monotonicMillis()) {
    /** The snackbar on screen. */
    var snackbar: OverlayEntry? by mutableStateOf(null)
        private set

    /** The banners on screen, highest priority first. */
    var banners: List<OverlayEntry> by mutableStateOf(emptyList())
        private set

    /** Snackbars waiting for the one on screen to leave. */
    var waiting: List<OverlayEntry> by mutableStateOf(emptyList())
        private set

    private var nextId = 0L

    /** Snackbar slots ([OverlaySnackbarSlot]) composed now; the newest draws the snackbar. */
    internal val slots = OverlaySlots()

    /** Queues [overlay] (or replaces the one with its key) and returns its handle. */
    fun show(overlay: Overlay): OverlayHandle = when (overlay) {
        is Overlay.Snackbar -> showSnackbar(overlay)
        is Overlay.Banner -> showBanner(overlay)
    }

    /** Removes the overlay and calls its [Overlay.onDismissed]. */
    fun dismiss(id: Long) {
        remove(id)?.overlay?.onDismissed?.invoke()
    }

    /** Removes the overlay and runs its action. */
    fun performAction(id: Long) {
        remove(id)?.overlay?.action?.onAction?.invoke()
    }

    /** Removes the overlay without calling anything. */
    fun withdraw(id: Long) {
        remove(id)
    }

    /** Whether the overlay [id] is showing or waiting. */
    fun isActive(id: Long): Boolean = find(id) != null

    /** Dismisses every timed overlay whose time on screen is up. */
    fun tick() {
        val now = clock()
        val expired = (listOfNotNull(snackbar) + banners).filter { it.expired(now) }
        expired.forEach { dismiss(it.id) }
    }

    /** Milliseconds until the next timed overlay is up, or null when nothing on screen is timed. */
    fun nextDeadlineIn(): Long? {
        val now = clock()
        return (listOfNotNull(snackbar) + banners)
            .mapNotNull { entry -> entry.overlay.durationMillis?.let { entry.shownAt + it - now } }
            .minOrNull()
            ?.coerceAtLeast(0L)
    }


    private fun showSnackbar(overlay: Overlay.Snackbar): OverlayHandle {
        val now = clock()
        val current = snackbar
        if (current != null && current.overlay.dedupeKey == overlay.dedupeKey) {
            snackbar = OverlayEntry(current.id, overlay, now)
            return OverlayHandle(this, current.id)
        }
        val queued = waiting.indexOfFirst { it.overlay.dedupeKey == overlay.dedupeKey }
        if (queued >= 0) {
            val entry = waiting[queued]
            waiting = waiting.toMutableList().also { it[queued] = OverlayEntry(entry.id, overlay, entry.shownAt) }
            return OverlayHandle(this, entry.id)
        }
        val entry = OverlayEntry(nextId++, overlay, now)
        when {
            current == null -> snackbar = entry
            overlay.priority > current.overlay.priority -> {
                waiting = listOf(current) + waiting
                snackbar = entry
            }
            else -> waiting = waiting + entry
        }
        return OverlayHandle(this, entry.id)
    }

    private fun showBanner(overlay: Overlay.Banner): OverlayHandle {
        val now = clock()
        val existing = banners.indexOfFirst { it.overlay.dedupeKey == overlay.dedupeKey }
        if (existing >= 0) {
            val id = banners[existing].id
            banners = banners.toMutableList().also { it[existing] = OverlayEntry(id, overlay, now) }.byPriority()
            return OverlayHandle(this, id)
        }
        val entry = OverlayEntry(nextId++, overlay, now)
        banners = (banners + entry).byPriority()
        return OverlayHandle(this, entry.id)
    }

    private fun remove(id: Long): OverlayEntry? {
        val entry = find(id) ?: return null
        when {
            snackbar?.id == id -> showNext()
            waiting.any { it.id == id } -> waiting = waiting.filterNot { it.id == id }
            else -> banners = banners.filterNot { it.id == id }
        }
        return entry
    }

    private fun showNext() {
        val next = waiting.sortedByDescending { it.overlay.priority }.firstOrNull()
        waiting = if (next == null) waiting else waiting.filterNot { it.id == next.id }
        // Its time on screen starts now, also for one a higher priority had pushed back.
        snackbar = next?.let { OverlayEntry(it.id, it.overlay, clock()) }
    }

    private fun find(id: Long): OverlayEntry? =
        snackbar?.takeIf { it.id == id } ?: waiting.firstOrNull { it.id == id } ?: banners.firstOrNull { it.id == id }

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

private fun List<OverlayEntry>.byPriority(): List<OverlayEntry> = sortedByDescending { it.overlay.priority }

/** How much an overlay matters next to the others in the queue. */
enum class OverlayPriority { Low, Normal, High, Critical }

/** A banner's colouring. */
enum class OverlayTone { Info, Error }

/** The standard times on screen. */
object OverlayDurations {
    const val SHORT: Long = 4_000L
    const val LONG: Long = 10_000L
}

/** An overlay's one action ("Undo", "Retry", "Restart"). */
class OverlayAction(val label: String, val onAction: () -> Unit)

/** What a surface asks the [OverlayHost] to show. */
sealed class Overlay(
    val message: String,
    /** Overlays with the same key are one overlay: a newer one replaces the older. Null keys by kind and message. */
    val key: String?,
    val priority: OverlayPriority,
    /** Time on screen; null stays until dismissed, acted on or withdrawn. */
    val durationMillis: Long?,
    val action: OverlayAction?,
    /** Shows a close control. */
    val dismissible: Boolean,
    /** Called when the overlay leaves other than through its action. */
    val onDismissed: () -> Unit,
) {
    internal val dedupeKey: String get() = key ?: "${kindName()}:$message"

    private fun kindName(): String = when (this) {
        is Snackbar -> "snackbar"
        is Banner -> "banner"
    }

    /** A transient message at the foot of the window (or the surface's [OverlaySnackbarSlot]). */
    class Snackbar(
        message: String,
        key: String? = null,
        priority: OverlayPriority = OverlayPriority.Normal,
        durationMillis: Long? = OverlayDurations.SHORT,
        action: OverlayAction? = null,
        dismissible: Boolean = false,
        onDismissed: () -> Unit = {},
    ) : Overlay(message, key, priority, durationMillis, action, dismissible, onDismissed)

    /** A notice across the top of the window: a runtime that is down, a connection lost. */
    class Banner(
        message: String,
        key: String? = null,
        priority: OverlayPriority = OverlayPriority.Normal,
        val tone: OverlayTone = OverlayTone.Info,
        durationMillis: Long? = null,
        action: OverlayAction? = null,
        dismissible: Boolean = false,
        onDismissed: () -> Unit = {},
    ) : Overlay(message, key, priority, durationMillis, action, dismissible, onDismissed)

    companion object {
        /**
         * An undo offer: [onUndo] when the person takes it; [onCommit] when it times out or is
         * dismissed, so the change stands.
         */
        fun undo(
            message: String,
            key: String,
            onUndo: () -> Unit,
            onCommit: () -> Unit,
            label: String = UNDO_LABEL,
        ): Snackbar = Snackbar(
            message = message,
            key = key,
            priority = OverlayPriority.High,
            durationMillis = OverlayDurations.LONG,
            action = OverlayAction(label, onUndo),
            dismissible = true,
            onDismissed = onCommit,
        )

        const val UNDO_LABEL = "Undo"
    }
}

/** One overlay the host holds, with when it reached the screen. */
@Immutable
class OverlayEntry internal constructor(val id: Long, val overlay: Overlay, internal val shownAt: Long) {
    internal fun expired(now: Long): Boolean = overlay.durationMillis?.let { now - shownAt >= it } ?: false
}

/** What [OverlayHost.show] returns: the owner's way to take its overlay back. */
class OverlayHandle internal constructor(private val host: OverlayHost, val id: Long) {
    val isActive: Boolean get() = host.isActive(id)

    fun dismiss() = host.dismiss(id)

    fun withdraw() = host.withdraw(id)
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
