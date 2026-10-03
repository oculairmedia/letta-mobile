package com.letta.mobile.ui.chat.surface.timeline

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * letta-mobile-29sxj: a Dispatchers.Main that runs only when [drain] is called, on the caller's
 * thread. Paging delivers its pages on Main; draining it between Compose frames keeps those state
 * writes off the composition's way, so a frame sees whatever Paging has delivered by then and the
 * run is repeatable.
 */
internal class ManualMainDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    /** Runs everything queued, including what that work queues in turn. */
    fun drain() {
        while (true) queue.poll()?.run() ?: return
    }
}
