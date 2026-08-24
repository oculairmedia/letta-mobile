package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private val resubscribe: suspend (String) -> Unit,
) {
    private val generation = atomic(0L)
    @Volatile
    private var observerJob: Job? = null
    @Volatile
    private var resubscribeJob: Job? = null
    @Volatile
    private var viewedConversationId: String? = null
    @Volatile
    private var viewedMessageListPath: String? = null

    fun onReady(handle: IrohConnectionHandle) {
        val readyGeneration = generation.incrementAndGet()
        startObserverIngest(handle, readyGeneration)
        reSubscribeViewedConversation(readyGeneration)
    }

    fun onNotReady(reason: String) {
        generation.incrementAndGet()
        resubscribeJob?.cancel()
        resubscribeJob = null
        stopObserverIngest(reason)
    }

    fun recordViewedConversation(method: String, path: String) {
        if (method != "message.list") return
        val conversationId = conversationIdFromMessageListPath(path) ?: return
        viewedConversationId = conversationId
        viewedMessageListPath = path
    }

    fun currentViewedConversationId(): String? = viewedConversationId

    private fun startObserverIngest(handle: IrohConnectionHandle, readyGeneration: Long) {
        val streamFrames = handle.effectiveObserverStreamFrames
        if (streamFrames == null) {
            stopObserverIngest("no_observer_stream")
            Telemetry.event("IrohObserver", "ingest.unavailable", "sessionId" to handle.sessionId)
            return
        }
        observerJob?.cancel()
        Telemetry.event(
            "IrohObserver", "ingest.start",
            "sessionId" to handle.sessionId,
            "generation" to readyGeneration.toString(),
        )
        observerJob = scope.launch {
            runCatching {
                streamFrames.collect { received ->
                    if (generation.value != readyGeneration) return@collect
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

    private fun stopObserverIngest(reason: String) {
        val job = observerJob ?: return
        observerJob = null
        job.cancel()
        Telemetry.event("IrohObserver", "ingest.stop", "reason" to reason)
    }

    private data class ReSubscription(
        val path: String,
        val conversationId: String?,
        val generation: Long,
    )

    private fun reSubscribeViewedConversation(readyGeneration: Long) {
        val path = viewedMessageListPath ?: return
        val request = ReSubscription(path, viewedConversationId, readyGeneration)
        resubscribeJob?.cancel()
        resubscribeJob = scope.launch { runReSubscription(request) }
    }

    private suspend fun runReSubscription(request: ReSubscription) {
        if (generation.value != request.generation) return
        Telemetry.event(
            "IrohObserver", "resubscribe.begin",
            "conversationId" to (request.conversationId ?: ""),
            "generation" to request.generation.toString(),
        )
        resubscribeIfCurrent(request)
    }

    private suspend fun resubscribeIfCurrent(request: ReSubscription) {
        if (generation.value != request.generation) return
        runCatching { resubscribe(request.path) }
            .onFailure { error -> reportResubscribeFailure(request, error) }
    }

    private fun reportResubscribeFailure(request: ReSubscription, error: Throwable) {
        if (error is CancellationException) throw error
        Telemetry.event(
            "IrohObserver", "resubscribe.failed",
            "conversationId" to (request.conversationId ?: ""),
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
        )
    }

    private fun conversationIdFromMessageListPath(path: String): String? {
        val marker = "/v1/conversations/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        val after = path.substring(start + marker.length)
        val id = after.substringBefore('/').substringBefore('?')
        return id.takeIf { it.isNotBlank() }
    }
}
