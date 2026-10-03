package com.letta.mobile.data.plugin.wire

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive

/**
 * The calls one [LcpPeer] has in flight, both ways, under one lock: its own requests waiting for an
 * answer (correlated by the id it minted) and the other side's requests its handlers are running
 * (by the other side's id, so `$/cancel` can find them). [inFlight] counts both, so a session can
 * drain before it deactivates.
 */
internal class LcpCallTable {
    private val lock = SynchronizedObject()
    private var nextId = 0L
    private val waiting = mutableMapOf<JsonPrimitive, CompletableDeferred<JsonRpcMessage>>()
    private val running = mutableMapOf<JsonPrimitive, Job>()
    private val cancelledByRemote = mutableSetOf<JsonPrimitive>()
    private val count = MutableStateFlow(0)

    val inFlight: StateFlow<Int> = count.asStateFlow()

    val runningCount: Int get() = synchronized(lock) { running.size }

    /** A fresh id and the answer it will get. */
    fun open(): Pair<JsonPrimitive, CompletableDeferred<JsonRpcMessage>> = synchronized(lock) {
        val id = JsonPrimitive(++nextId)
        val answer = CompletableDeferred<JsonRpcMessage>()
        waiting[id] = answer
        recount()
        id to answer
    }

    /** Hands [message] to the call [id] waits on; an answer to no waiting call (late, or never asked) is dropped. */
    fun answer(id: JsonPrimitive, message: JsonRpcMessage): Boolean {
        val waiter = synchronized(lock) { waiting.remove(id)?.also { recount() } } ?: return false
        return waiter.complete(message)
    }

    fun forget(id: JsonPrimitive) = synchronized(lock) {
        if (waiting.remove(id) != null) recount()
    }

    /** Whether the other side's request [id] is still running (a second one with that id is a duplicate). */
    fun isRunning(id: JsonPrimitive): Boolean = synchronized(lock) { id in running }

    /** Records a request of the other side; only the reader admits, after [isRunning] said no. */
    fun admit(id: JsonPrimitive, job: Job) = synchronized(lock) {
        running[id] = job
        recount()
    }

    fun finish(id: JsonPrimitive) = synchronized(lock) {
        running.remove(id)
        cancelledByRemote.remove(id)
        recount()
    }

    /** Cancels the other side's request [id] at its asking. */
    fun cancel(id: JsonPrimitive) {
        val job = synchronized(lock) { running[id]?.also { cancelledByRemote += id } }
        job?.cancel()
    }

    fun wasCancelledByRemote(id: JsonPrimitive): Boolean = synchronized(lock) { id in cancelledByRemote }

    /** Ends every waiting call with [failure] (the session or transport closed). */
    fun failAll(failure: JsonRpcError) {
        val all = synchronized(lock) { waiting.toMap().also { waiting.clear(); recount() } }
        all.forEach { (id, answer) -> answer.complete(JsonRpcMessage.Failure(id, failure)) }
    }

    private fun recount() {
        count.value = waiting.size + running.size
    }
}
