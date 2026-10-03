package com.letta.mobile.ui.canvas.plugin

import com.letta.mobile.data.plugin.view.PostMessagePort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The [PostMessagePort] between a `ViewBridge` and the page a [PluginViewHost] shows: the bridge
 * end is the port itself, the page end is [postFromPage] and [toPage].
 *
 * What the page posts is held in a bounded buffer, and messages past it are dropped, so a page
 * that floods its host costs a fixed amount of memory (the bridge refuses floods by rate anyway).
 * What the bridge sends waits for the host to deliver it, since the bridge only answers what the
 * page asked and so cannot outrun it. Both ends stop at [close].
 */
class PluginPageChannel(capacity: Int = DEFAULT_CAPACITY) : PostMessagePort {
    private val fromPage = Channel<String>(capacity, BufferOverflow.DROP_LATEST)
    private val toPageQueue = Channel<String>(Channel.UNLIMITED)

    override val incoming: Flow<String> = fromPage.receiveAsFlow()

    override suspend fun send(json: String) {
        toPageQueue.trySend(json)
    }

    /** What the bridge sends the page, in order, for the host to hand to `window.__lettaViewReceive`. */
    val toPage: Flow<String> = toPageQueue.receiveAsFlow()

    /** The page posted [json]; callable from any thread. False when it was dropped (buffer full or closed). */
    fun postFromPage(json: String): Boolean = fromPage.trySend(json).isSuccess

    /** Ends both directions; later messages are dropped. */
    fun close() {
        fromPage.close()
        toPageQueue.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64
    }
}
