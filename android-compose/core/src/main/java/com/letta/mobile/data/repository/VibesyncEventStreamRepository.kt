package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.VibesyncEvent
import com.letta.mobile.data.model.VibesyncRawEventEnvelope
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.stream.SseParser
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

@Singleton
open class VibesyncEventStreamRepository internal constructor(
    private val apiClient: LettaApiClient,
    private val scope: CoroutineScope,
) : IVibesyncEventStreamRepository {
    @Inject
    constructor(apiClient: LettaApiClient) : this(
        apiClient = apiClient,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val activeSubscribers = AtomicInteger(0)
    private val _events = MutableSharedFlow<VibesyncEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VibesyncEvent> = _events.asSharedFlow()
    private var streamJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Synchronized
    override fun start() {
        if (activeSubscribers.incrementAndGet() > 1) return
        if (streamJob?.isActive == true) return
        streamJob = scope.launch { runStreamLoop() }
    }

    @Synchronized
    override fun stop() {
        val remaining = activeSubscribers.decrementAndGet()
        if (remaining > 0) return
        activeSubscribers.set(0)
        val job = streamJob
        streamJob = null
        scope.launch { job?.cancelAndJoin() }
    }

    private suspend fun runStreamLoop() {
        var backoffMs = 1_000L
        while (scope.isActive && activeSubscribers.get() > 0) {
            try {
                connectOnce()
                backoffMs = 1_000L
            } catch (_: EndpointUnavailableException) {
                // 404 means the backend doesn't expose vibesync events at
                // all — retrying serves no purpose and the retry storm
                // pressures the GC enough to jank the UI. Log once and
                // exit the loop.
                Log.i(TAG, "vibesync event stream not available on this backend; not retrying")
                return
            } catch (error: Throwable) {
                Log.i(TAG, "vibesync event stream unavailable", error)
            }
            if (activeSubscribers.get() <= 0) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun connectOnce() {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')
        val response = client.get("$baseUrl/api/events/stream")
        when (response.status.value) {
            in 200..299 -> Unit
            404 -> throw EndpointUnavailableException()
            else -> throw IllegalStateException("Vibesync event stream failed with HTTP ${response.status.value}")
        }
        val channel = response.body<ByteReadChannel>()
        SseParser.parseRawEvents(channel).collect { raw ->
            routeRawEvent(raw.event, raw.data, raw.id)?.let { _events.emit(it) }
        }
    }

    /**
     * Thrown when the backend reports 404 for `/api/events/stream`. Distinct
     * from a transient failure: the endpoint is not deployed and no amount
     * of retrying will change that, so the stream loop exits instead of
     * looping forever and burning heap.
     */
    private class EndpointUnavailableException : Exception()

    internal fun routeRawEvent(eventName: String?, rawData: String, id: String?): VibesyncEvent? {
        val envelope = runCatching { json.decodeFromString<VibesyncRawEventEnvelope>(rawData) }.getOrNull()
        val dataObject = envelope?.data ?: runCatching { json.parseToJsonElement(rawData).jsonObject }.getOrNull()
        val type = eventName ?: envelope?.event ?: envelope?.type ?: dataObject?.stringField("event") ?: dataObject?.stringField("type")
        return type?.let { VibesyncEvent(type = it, data = dataObject, id = id) }
    }

    private fun JsonObject.stringField(name: String): String? = get(name)?.let { element ->
        runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    private companion object {
        const val TAG = "VibesyncEvents"
    }
}
