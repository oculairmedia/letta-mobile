package com.letta.mobile.bot.message

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-agent message queue with serial processing and locking.
 * Kotlin equivalent of lettabot's per-bot message queue that ensures
 * messages are processed one at a time to avoid race conditions in
 * the agent's conversation state.
 *
 * @param T The message type to queue.
 */
class MessageQueue<T>(
    private val capacity: Int = Channel.BUFFERED,
) {
    private val mutex = Mutex()
    private val channel = Channel<T>(capacity)
    private var processing = false

    /** Enqueue a message for processing. */
    suspend fun enqueue(item: T) {
        channel.send(item)
    }

    /**
     * Process queued messages serially with the given handler.
     * Only one invocation of [handler] runs at a time per queue.
     * Call this in a coroutine loop.
     */
    suspend fun processNext(handler: suspend (T) -> Unit) {
        val item = channel.receive()
        mutex.withLock {
            processing = true
            try {
                handler(item)
            } catch (e: Exception) {
                Log.w("MessageQueue", "Error processing queued message", e)
            } finally {
                processing = false
            }
        }
    }

    /** Whether a message is currently being processed. */
    val isProcessing: Boolean get() = processing

    /** Cancel the queue and close the channel. */
    fun close() {
        channel.close()
    }
}
