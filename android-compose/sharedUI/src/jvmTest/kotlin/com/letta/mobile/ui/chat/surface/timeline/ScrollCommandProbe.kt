package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicInteger

/**
 * letta-mobile-29sxj: counts the scroll commands a list is given, not where it ends up.
 *
 * `scrollToItem(0)` on a list already at its newest edge moves nothing, so the position cannot say
 * whether the page issued it. Every scroll command (user, animation or `scrollToItem`) raises the
 * list's `isScrolling` flag for its duration, so the probe counts the flag's rising edges. The flag
 * lives in a private field of Foundation's scroll state; the lookup fails loudly if a Compose
 * upgrade moves it, rather than silently counting nothing.
 */
internal class ScrollCommandProbe(list: LazyListState) : AutoCloseable {
    private val count = AtomicInteger()
    private var lastRead = 0
    private val scrolling = scrollingFlagOf(list)
    private val observer: ObserverHandle = Snapshot.registerGlobalWriteObserver { written ->
        if (written === scrolling && scrolling.value) count.incrementAndGet()
    }

    /** Scroll commands since the previous call. */
    fun takeDelta(): Int {
        val now = count.get()
        return (now - lastRead).also { lastRead = now }
    }

    override fun close() = observer.dispose()

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun scrollingFlagOf(list: LazyListState): MutableState<Boolean> {
            val scrollable = field(list, "scrollableState")
            return field(scrollable, "isScrollingState") as MutableState<Boolean>
        }

        fun field(owner: kotlin.Any, name: String): kotlin.Any {
            val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
            return checkNotNull(field.get(owner)) { "${owner.javaClass.name}.$name is null" }
        }
    }
}
