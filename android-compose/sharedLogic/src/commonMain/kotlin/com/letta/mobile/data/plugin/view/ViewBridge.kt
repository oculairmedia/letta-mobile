package com.letta.mobile.data.plugin.view

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/** Where a view is in its life: waiting for `view.ready`, live, being torn down, gone. */
enum class ViewBridgeState {
    AWAITING_READY,
    READY,
    TEARING_DOWN,
    CLOSED,
    ;

    val isOpen: Boolean get() = this == AWAITING_READY || this == READY
}

/** A bridge's limits and its audit; [clockMs] is a monotonic millisecond clock for the rate limit. */
data class ViewBridgeOptions(
    val audit: ViewAuditSink = ViewAuditSink.ToTelemetry,
    val maxMessagesPerSecond: Int = LcpView.MAX_MESSAGES_PER_SECOND,
    val teardownTimeoutMs: Long = LcpView.TEARDOWN_TIMEOUT_MS,
    val clockMs: () -> Long = monotonicClock(),
)

private fun monotonicClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/**
 * One live view's end of `lcp-view/1` (plan section 7.2, letta-mobile-s416w.31). Every message the
 * page posts on [port] is validated ([ViewMessageValidator]), rate limited per view, gated by the
 * view's state (nothing but `view.ready` before the handshake, nothing at all after teardown) and
 * audited; the calls that pass go to [ViewCallHandler], which relays actions over the transport.
 *
 * A platform host creates one per view, runs [run] for as long as the page lives, pushes
 * [pushContext] / [elementChanged] when they change, and ends it with [teardown].
 */
class ViewBridge(
    val spec: PluginViewSpec,
    private val port: PostMessagePort,
    private val services: ViewBridgeServices,
    private val options: ViewBridgeOptions = ViewBridgeOptions(),
) {
    private val handler = ViewCallHandler(spec, services)
    private val limiter = ViewRateLimiter(options.maxMessagesPerSecond)
    private val mutableState = MutableStateFlow(ViewBridgeState.AWAITING_READY)
    private val pending = MutableStateFlow<PendingRequest?>(null)
    private val hostRequests = MutableStateFlow(0)

    val state: StateFlow<ViewBridgeState> = mutableState.asStateFlow()

    /** Reads the page's messages until the view is closed; answers run concurrently, so a slow action never holds the teardown. */
    suspend fun run() {
        coroutineScope {
            val reader = launch {
                port.incoming.collect { raw -> receive(raw)?.let { call -> launch { answer(call) } } }
            }
            mutableState.first { it == ViewBridgeState.CLOSED }
            reader.cancel()
        }
    }

    /** Sends `host.context` again (theme, settings or display mode changed); false unless the view is ready. */
    suspend fun pushContext(): Boolean = pushIfReady(HostMethod.CONTEXT, services.host.context().toJson())

    /** Sends `host.element.changed` with [element]; false unless the view is ready. */
    suspend fun elementChanged(element: ViewElement): Boolean =
        pushIfReady(HostMethod.ELEMENT_CHANGED, buildJsonObject { put("element", ViewHostContext.elementJson(element)) })

    /**
     * Ends the view: a ready page gets `host.teardown { reason }` and up to the teardown timeout to
     * answer. True when it answered in time; the bridge is closed either way.
     */
    suspend fun teardown(reason: String): Boolean {
        val prior = mutableState.getAndUpdate { if (it.isOpen) ViewBridgeState.TEARING_DOWN else it }
        if (!prior.isOpen) return false
        val acknowledged = prior == ViewBridgeState.READY && awaitTeardown(reason)
        mutableState.value = ViewBridgeState.CLOSED
        return acknowledged
    }

    private suspend fun awaitTeardown(reason: String): Boolean {
        val id = JsonPrimitive("host-" + hostRequests.updateAndGet { it + 1 })
        val request = PendingRequest(id, CompletableDeferred())
        pending.value = request
        send(ViewRpc.request(id, HostMethod.TEARDOWN, buildJsonObject { put("reason", reason) }), HostMethod.TEARDOWN)
        val answered = withTimeoutOrNull(options.teardownTimeoutMs) { request.answered.await() } != null
        pending.value = null
        return answered
    }

    /** The call to answer concurrently, or null when [raw] was refused, was a reply, or was the handshake (answered here, in order). */
    private suspend fun receive(raw: String): ViewInbound.Call? {
        val inbound = ViewMessageValidator.validate(raw)
        admissionRefusal(inbound)?.let { refusal ->
            refuse(inbound.id, refusal, methodOf(inbound))
            return null
        }
        return when (inbound) {
            is ViewInbound.Reply -> settle(inbound)
            is ViewInbound.Call -> gate(inbound)
            is ViewInbound.Refused -> null
        }
    }

    private fun admissionRefusal(inbound: ViewInbound): ViewRpcError? = when {
        inbound is ViewInbound.Refused -> inbound.error
        !limiter.tryAcquire(options.clockMs()) -> ViewRpcError(ViewErrorCode.RATE_LIMITED, "at most ${options.maxMessagesPerSecond} messages a second")
        else -> null
    }

    private fun settle(reply: ViewInbound.Reply): ViewInbound.Call? {
        val request = pending.value?.takeIf { it.id == reply.id }
        request?.answered?.complete(Unit)
        audit(ViewDirection.VIEW_TO_HOST, HostMethod.TEARDOWN, if (request == null) ViewErrorCode.INVALID_REQUEST else null)
        return null
    }

    private suspend fun gate(call: ViewInbound.Call): ViewInbound.Call? = when (mutableState.value) {
        ViewBridgeState.READY -> call
        ViewBridgeState.AWAITING_READY -> handshake(call)
        ViewBridgeState.TEARING_DOWN, ViewBridgeState.CLOSED -> refuseCall(call, ViewErrorCode.CLOSED, "the view is closing")
    }

    private suspend fun handshake(call: ViewInbound.Call): ViewInbound.Call? {
        if (call.method != ViewMethod.READY) return refuseCall(call, ViewErrorCode.NOT_READY, "send view.ready first")
        val result = handler.ready(call.params)
        if (result is ViewCallResult.Ok) mutableState.compareAndSet(ViewBridgeState.AWAITING_READY, ViewBridgeState.READY)
        reply(call, result)
        return null
    }

    private suspend fun answer(call: ViewInbound.Call) {
        reply(call, handler.handle(call.method, call.params))
    }

    private suspend fun reply(call: ViewInbound.Call, result: ViewCallResult) {
        val error = (result as? ViewCallResult.Err)?.error
        audit(ViewDirection.VIEW_TO_HOST, call.method.wire, error?.code)
        val id = call.id ?: return
        val message = when (result) {
            is ViewCallResult.Ok -> ViewRpc.result(id, result.result)
            is ViewCallResult.Err -> ViewRpc.error(id, result.error)
        }
        send(message, null)
    }

    private suspend fun refuseCall(call: ViewInbound.Call, code: ViewErrorCode, message: String): ViewInbound.Call? {
        refuse(call.id, ViewRpcError(code, message), call.method.wire)
        return null
    }

    /** Audits a refusal and answers it when the message had an id; notifications are never answered. */
    private suspend fun refuse(id: JsonPrimitive?, error: ViewRpcError, method: String?) {
        audit(ViewDirection.VIEW_TO_HOST, method, error.code)
        if (id != null || error.code.answersWithoutId) send(ViewRpc.error(id, error), null)
    }

    private suspend fun pushIfReady(method: String, params: JsonObject): Boolean {
        if (mutableState.value != ViewBridgeState.READY) return false
        return send(ViewRpc.notification(method, params), method)
    }

    /** Sends [message] to the page unless it is over the size cap; [method] names host requests and notifications for the audit. */
    private suspend fun send(message: JsonObject, method: String?): Boolean {
        val text = message.toString()
        val tooLarge = text.encodeToByteArray().size > LcpView.MAX_MESSAGE_BYTES
        if (method != null || tooLarge) audit(ViewDirection.HOST_TO_VIEW, method, ViewErrorCode.TOO_LARGE.takeIf { tooLarge })
        if (tooLarge) return false
        port.send(text)
        return true
    }

    private fun audit(direction: ViewDirection, method: String?, refusal: ViewErrorCode?) {
        options.audit.record(ViewAuditEntry(spec, direction, method, refusal))
    }

    private fun methodOf(inbound: ViewInbound): String? = (inbound as? ViewInbound.Refused)?.method ?: (inbound as? ViewInbound.Call)?.method?.wire

    private class PendingRequest(val id: JsonPrimitive, val answered: CompletableDeferred<Unit>)
}

/** JSON-RPC answers a message it could not read an id from (`id: null`) only for parse and envelope errors. */
private val ViewErrorCode.answersWithoutId: Boolean
    get() = this == ViewErrorCode.PARSE_ERROR || this == ViewErrorCode.INVALID_REQUEST || this == ViewErrorCode.TOO_LARGE
