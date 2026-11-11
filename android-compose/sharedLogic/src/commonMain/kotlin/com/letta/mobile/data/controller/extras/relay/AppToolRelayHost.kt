package com.letta.mobile.data.controller.extras.relay

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * One app connection's end of the relay on the host. The host's connection creates it, routes the
 * app's [AppToolRelay.RESPONSE_TYPE] frames to [receive] and calls [close] when the connection ends;
 * every call still waiting then fails at once instead of waiting out its timeout.
 */
class AppToolRelayLink internal constructor(
    private val send: suspend (String) -> Boolean,
    private val ready: () -> Boolean,
) {
    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<AppToolCallId, CompletableDeferred<AppToolCallResult>>()

    @Volatile
    private var closed = false

    /** Whether the app on this connection signed in and advertised [AppToolRelay.CAPABILITY]. */
    val acceptsCalls: Boolean get() = !closed && ready()

    /**
     * Answers the call this [frame] names. A response for no waiting call (a duplicate, or one that
     * arrived after its timeout) is ignored.
     *
     * @return true when the frame answered a waiting call.
     */
    fun receive(frame: JsonObject): Boolean {
        val response = AppToolRelayFrames.decodeResponse(frame) ?: return false
        val waiting = synchronized(lock) { pending.remove(response.callId) } ?: return false
        return waiting.complete(response.result)
    }

    /** The connection ended: every waiting call fails now, and no new call is accepted. */
    fun close() {
        val waiting = synchronized(lock) {
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        waiting.forEach { it.complete(AppToolCallResult(DISCONNECTED, isError = true)) }
    }

    /** Sends [request]; the answer completes the returned deferred. Null when it could not be sent. */
    internal suspend fun deliver(request: AppToolCallRequest): CompletableDeferred<AppToolCallResult>? {
        val answer = CompletableDeferred<AppToolCallResult>()
        val accepted = synchronized(lock) {
            if (!closed) pending[request.callId] = answer
            !closed
        }
        if (!accepted) return null
        val sent = send(AppToolRelayFrames.encode(request))
        if (!sent) forget(request.callId)
        return answer.takeIf { sent }
    }

    /** Stops waiting for [callId]; its answer, if it still comes, is ignored. */
    internal fun forget(callId: AppToolCallId) {
        synchronized(lock) { pending.remove(callId) }
    }

    private companion object {
        const val DISCONNECTED = "The app showing this conversation disconnected before it answered; the call may still have applied."
    }
}

/**
 * The host's side of the app-tool relay: the host tools that stand in for app tools [call] it, and
 * it forwards each call to the app viewing the caller's conversation and returns its answer. The
 * scope is the one the host's App Server stamped on the call, never model input. With no such app
 * the call fails at once; an app that does not answer within [timeout] fails it too, so a turn never
 * hangs. Only [allowedTools] are ever relayed.
 *
 * The host's Iroh endpoint supplies the apps viewing a conversation ([bindViewers]); each of its app
 * connections owns an [AppToolRelayLink] made by [link].
 *
 * @param allowedTools the tool names this host relays.
 * @param timeout how long an app has to answer.
 */
@OptIn(ExperimentalUuidApi::class)
class AppToolRelayHost(
    private val allowedTools: Set<String>,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val newCallId: () -> AppToolCallId = { AppToolCallId("app-tool-${Uuid.random()}") },
) {
    @Volatile
    private var viewers: suspend (String) -> List<AppToolRelayLink> = { emptyList() }

    /** Where calls find the links of the apps viewing a conversation (by its id), oldest viewer first. */
    fun bindViewers(lookup: suspend (String) -> List<AppToolRelayLink>) {
        viewers = lookup
    }

    /**
     * A link for one app connection.
     *
     * @param send writes one frame to the app's control stream; false when it could not.
     * @param ready whether the app signed in and advertised [AppToolRelay.CAPABILITY].
     */
    fun link(send: suspend (String) -> Boolean, ready: () -> Boolean): AppToolRelayLink = AppToolRelayLink(send, ready)

    /**
     * Runs [toolName] on the app viewing [scope]'s conversation: the newest viewer that accepts
     * relayed calls gets it, an older one only when sending to the newer failed.
     */
    suspend fun call(toolName: String, input: JsonObject, scope: AppToolCallScope): AppToolCallResult {
        if (toolName !in allowedTools) return AppToolCallResult("$toolName is not relayed to the app.", isError = true)
        val conversation = scope.conversationId?.takeIf { it.isNotBlank() } ?: return noApp(toolName)
        val candidates = viewers(conversation).filter { it.acceptsCalls }.asReversed()
        val request = AppToolCallRequest(newCallId(), toolName, input, scope)
        for (link in candidates) {
            val answer = link.deliver(request) ?: continue
            return try {
                withTimeoutOrNull(timeout) { answer.await() } ?: timedOut(toolName)
            } finally {
                link.forget(request.callId)
            }
        }
        return noApp(toolName)
    }

    private fun noApp(toolName: String) = AppToolCallResult(
        "No app is showing this conversation that can run $toolName, so nothing changed. " +
            "Ask the person to open this conversation in the app (an up-to-date version), then try again.",
        isError = true,
    )

    private fun timedOut(toolName: String) = AppToolCallResult(
        "The app showing this conversation did not answer $toolName within ${timeout.inWholeSeconds}s; " +
            "the call may still have applied.",
        isError = true,
    )

    /** The default answer window. */
    companion object {
        /** How long an app has to answer by default; a layout change is instant, so this is generous. */
        val DEFAULT_TIMEOUT: Duration = 20.seconds
    }
}
