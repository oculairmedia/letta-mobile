package com.letta.mobile.runtime.local

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ToolCall
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * letta-mobile-by4vm.121: one LiteRT-LM reply, streamed so it can be stopped.
 *
 * The blocking `sendMessage` could not be interrupted: a looping model held the single engine
 * until its whole context filled. Here every streamed text chunk goes through [generation]'s guard
 * (output cap, stop sequences, repetition), and a stop, from the guard or from the bridge
 * (client disconnect, timeout), calls [Conversation.cancelProcess], which ends the native run.
 * With automatic tool calling off, the model's tool calls arrive as messages of their own
 * (by4vm.121.16) and are collected for the reply.
 */
internal class LiteRtLmStreamingRun(
    private val conversation: Conversation,
    private val generation: OnDeviceGeneration,
) {
    private val done = CountDownLatch(1)
    private val toolCalls = CopyOnWriteArrayList<ToolCall>()

    @Volatile private var failure: Throwable? = null

    /** Streams the reply to [message] into [generation]; returns the tool calls the model made. */
    fun run(message: Message): List<ToolCall> {
        generation.onCancel { runCatching { conversation.cancelProcess() } }
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                toolCalls += message.toolCalls
                val text = LiteRtLmConversationMapping.textOf(message)
                if (text.isNotEmpty() && !generation.accept(text)) runCatching { conversation.cancelProcess() }
            }

            override fun onDone() = done.countDown()

            override fun onError(throwable: Throwable) {
                failure = throwable
                done.countDown()
            }
        }
        conversation.sendMessageAsync(message, callback, emptyMap())
        awaitDone()
        // A run we stopped ends in a cancellation error: that is the expected end, not a failure.
        failure?.takeIf { !generation.isStopped }?.let { throw it }
        return toolCalls.toList()
    }

    /** Waits for the native run; once stopped, only [CANCEL_GRACE_MS] more, never forever. */
    private fun awaitDone() {
        var stoppedAt = 0L
        while (!done.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!generation.isStopped) continue
            if (stoppedAt == 0L) stoppedAt = System.currentTimeMillis()
            check(System.currentTimeMillis() - stoppedAt < CANCEL_GRACE_MS) {
                "LiteRT-LM did not stop within ${CANCEL_GRACE_MS / 1_000} s of cancelProcess (${generation.stopReason?.wire})."
            }
        }
    }

    private companion object {
        const val POLL_MS = 250L
        const val CANCEL_GRACE_MS = 30_000L
    }
}
