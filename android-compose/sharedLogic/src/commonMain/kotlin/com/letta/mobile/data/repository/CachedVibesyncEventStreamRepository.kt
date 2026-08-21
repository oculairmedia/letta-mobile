package com.letta.mobile.data.repository

import com.letta.mobile.data.model.VibesyncEvent
import com.letta.mobile.data.model.VibesyncRawEventEnvelope
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.repository.api.NoOpVibesyncEventStreamLogger
import com.letta.mobile.data.repository.api.VibesyncEventStreamLogger
import com.letta.mobile.data.repository.api.VibesyncEventStreamSource
import com.letta.mobile.data.repository.api.VibesyncStreamEndpointUnavailableException
import com.letta.mobile.data.stream.SseParser
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import kotlin.time.Duration.Companion.milliseconds

/** Phase 5o: platform-neutral vibesync SSE stream repository. */
open class CachedVibesyncEventStreamRepository(
    private val streamSource: VibesyncEventStreamSource,
    private val scope: CoroutineScope,
    private val logger: VibesyncEventStreamLogger = NoOpVibesyncEventStreamLogger,
) : IVibesyncEventStreamRepository {
    private val activeSubscribers = atomic(0)
    private val lifecycleLock = SynchronizedObject()
    private val _events = MutableSharedFlow<VibesyncEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VibesyncEvent> = _events.asSharedFlow()
    private var streamJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun start() {
        synchronized(lifecycleLock) {
            if (activeSubscribers.incrementAndGet() > 1) return
            if (streamJob?.isActive == true) return
            streamJob = scope.launch { runStreamLoop() }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            val remaining = activeSubscribers.decrementAndGet()
            if (remaining > 0) return@synchronized
            activeSubscribers.value = 0
            val job = streamJob
            streamJob = null
            scope.launch { job?.cancelAndJoin() }
            Unit
        }
    }

    private suspend fun runStreamLoop() {
        var backoffMs = 1_000L
        while (scope.isActive && activeSubscribers.value > 0) {
            try {
                connectOnce()
                backoffMs = 1_000L
            } catch (_: VibesyncStreamEndpointUnavailableException) {
                logger.info("vibesync event stream not available on this backend; not retrying")
                return
            } catch (error: Throwable) {
                logger.info("vibesync event stream unavailable", error)
            }
            if (activeSubscribers.value <= 0) break
            delay(backoffMs.milliseconds)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun connectOnce() {
        val channel = streamSource.openStream()
        SseParser.parseRawEvents(channel).collect { raw ->
            routeRawEvent(raw.event, raw.data, raw.id)?.let { _events.emit(it) }
        }
    }

    internal fun routeRawEvent(eventName: String?, rawData: String, id: String?): VibesyncEvent? {
        val envelope = runCatching { json.decodeFromString<VibesyncRawEventEnvelope>(rawData) }.getOrNull()
        val dataObject = envelope?.data ?: runCatching { json.parseToJsonElement(rawData).jsonObject }.getOrNull()
        val type = eventName ?: envelope?.event ?: envelope?.type ?: dataObject?.stringField("event") ?: dataObject?.stringField("type")
        return type?.let { VibesyncEvent(type = it, data = dataObject, id = id) }
    }

    private fun JsonObject.stringField(name: String): String? = get(name)?.let { element ->
        runCatching { element.jsonPrimitive.content }.getOrNull()
    }
}
