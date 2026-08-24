package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns work that is scoped to one logical Iroh connection session.
 *
 * A Ready handle receives a monotonically increasing generation. Observer ingestion
 * and reconnect viewer registration verify that generation before touching the
 * transport, so work started for a disconnected handle cannot affect its successor.
 */
internal class IrohConnectionSession(
    private val scope: CoroutineScope,
    private val ingestObserverFrame: suspend (AppServerReceivedFrame) -> Unit,
    private val resubscribe: suspend (IrohViewedConversation) -> Unit,
) {
    private val generation = atomic(0L)
    @Volatile
    private var observerJob: Job? = null
    private val stoppedObserverJobs = ConcurrentHashMap.newKeySet<Job>()
    @Volatile
    private var resubscribeJob: Job? = null
    @Volatile
    private var viewedConversation: IrohViewedConversation? = null

    fun onReady(handle: IrohConnectionHandle) {
        val readyGeneration = nextGeneration()
        startObserverIngest(handle, readyGeneration)
        reSubscribeViewedConversation(readyGeneration)
    }

    fun onNotReady() {
        stopSessionWork(SessionStopReason.ConnectionStateChanged)
    }

    /** Stops the observer before callers reset state it exclusively owns. */
    suspend fun stopAndJoin() {
        stopSessionWork(SessionStopReason.Disconnect)
        stoppedObserverJobs.toList().forEach { job ->
            job.join()
            stoppedObserverJobs.remove(job)
        }
    }

    private fun nextGeneration() = IrohSessionGeneration(generation.incrementAndGet())

    private fun stopSessionWork(reason: SessionStopReason): Job? {
        nextGeneration()
        resubscribeJob?.cancel()
        resubscribeJob = null
        return stopObserverIngest(reason)
    }

    fun recordViewedConversation(conversation: IrohViewedConversation) {
        viewedConversation = conversation
    }

    fun currentViewedConversationId(): ConversationId? = viewedConversation?.id

    private fun startObserverIngest(handle: IrohConnectionHandle, readyGeneration: IrohSessionGeneration) {
        val streamFrames = handle.effectiveObserverStreamFrames
        if (streamFrames == null) {
            stopObserverIngest(SessionStopReason.ObserverStreamUnavailable)
            Telemetry.event("IrohObserver", "ingest.unavailable", "sessionId" to handle.sessionId)
            return
        }
        observerJob?.let { priorObserver ->
            priorObserver.cancel()
            stoppedObserverJobs.add(priorObserver)
        }
        Telemetry.event(
            "IrohObserver", "ingest.start",
            "sessionId" to handle.sessionId,
            "generation" to readyGeneration.toString(),
        )
        observerJob = scope.launch {
            runCatching {
                streamFrames.collect { received ->
                    if (!isCurrent(readyGeneration)) return@collect
                    ingestObserverFrame(received)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Telemetry.event(
                    "IrohObserver", "ingest.failed",
                    "error" to (error.message ?: error.toString()),
                    "class" to error::class.simpleName,
                )
            }
        }
    }

    private fun isCurrent(candidate: IrohSessionGeneration): Boolean = generation.value == candidate.value

    private fun stopObserverIngest(reason: SessionStopReason): Job? {
        val job = observerJob ?: return null
        observerJob = null
        stoppedObserverJobs.add(job)
        job.cancel()
        Telemetry.event("IrohObserver", "ingest.stop", "reason" to reason.telemetryValue)
        return job
    }

    private data class ReSubscription(
        val conversation: IrohViewedConversation,
        val generation: IrohSessionGeneration,
    )

    private fun reSubscribeViewedConversation(readyGeneration: IrohSessionGeneration) {
        val conversation = viewedConversation ?: return
        val request = ReSubscription(conversation, readyGeneration)
        resubscribeJob?.cancel()
        resubscribeJob = scope.launch { runReSubscription(request) }
    }

    private suspend fun runReSubscription(request: ReSubscription) {
        if (!isCurrent(request.generation)) return
        Telemetry.event(
            "IrohObserver", "resubscribe.begin",
            "conversationId" to request.conversation.id.value,
            "generation" to request.generation.toString(),
        )
        resubscribeIfCurrent(request)
    }

    private suspend fun resubscribeIfCurrent(request: ReSubscription) {
        if (!isCurrent(request.generation)) return
        runCatching { resubscribe(request.conversation) }
            .onFailure { error -> reportResubscribeFailure(request, error) }
    }

    private fun reportResubscribeFailure(request: ReSubscription, error: Throwable) {
        if (error is CancellationException) throw error
        Telemetry.event(
            "IrohObserver", "resubscribe.failed",
            "conversationId" to request.conversation.id.value,
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
        )
    }

    private enum class SessionStopReason(val telemetryValue: String) {
        ConnectionStateChanged("connection_state_changed"),
        Disconnect("disconnect"),
        ObserverStreamUnavailable("no_observer_stream"),
    }
}

/** A validated message-list route for the conversation currently viewed on this connection. */
internal data class IrohViewedConversation(
    val id: ConversationId,
    val messageListPath: String,
) {
    companion object {
        fun fromMessageListPath(path: String): IrohViewedConversation? {
            val marker = "/v1/conversations/"
            val start = path.indexOf(marker)
            if (start < 0) return null
            val id = path.substring(start + marker.length).substringBefore('/').substringBefore('?')
                .takeIf { it.isNotBlank() }
                ?: return null
            return IrohViewedConversation(ConversationId(id), path)
        }
    }
}

@JvmInline
private value class IrohSessionGeneration(val value: Long) {
    override fun toString(): String = value.toString()
}
