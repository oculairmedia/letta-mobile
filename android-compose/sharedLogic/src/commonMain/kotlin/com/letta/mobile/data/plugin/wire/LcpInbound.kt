package com.letta.mobile.data.plugin.wire

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** A request of the other side, admitted and about to run. */
private data class InboundCall(val id: JsonPrimitive, val method: LcpMethod, val params: JsonObject, val handler: LcpHandler)

/** What becomes of a request of the other side: it runs, or an error answers it. */
private sealed interface InboundVerdict {
    data class Run(val call: InboundCall) : InboundVerdict

    data class Answer(val error: JsonRpcError) : InboundVerdict
}

/**
 * The reading half of an [LcpPeer]: decodes each frame, hands answers to the calls waiting on
 * them, runs the other side's requests concurrently (each under its deadline, cancellable by
 * `$/cancel`) and its notifications in order, and answers every malformed or refused call.
 */
internal class LcpInbound(
    private val config: LcpPeerConfig,
    private val calls: LcpCallTable,
    private val io: LcpIo,
    private val scope: CoroutineScope,
) {
    private val handlers = mutableMapOf<LcpMethod, LcpHandler>()
    private val notifications = Channel<Pair<LcpHandler, JsonObject>>(NOTIFICATION_QUEUE)

    fun register(method: LcpMethod, handler: LcpHandler) {
        require(method != LcpMethod.CANCEL && method.direction.deliversTo(config.side)) { "a ${config.side} peer does not serve ${method.wire}" }
        handlers[method] = handler
    }

    /** Reads until the transport ends. */
    suspend fun run() = coroutineScope {
        launch { for ((handler, params) in notifications) runNotification(handler, params) }
        try {
            while (true) onFrame(config.side, io.receive() ?: break)
        } finally {
            notifications.close()
        }
    }

    private suspend fun onFrame(side: LcpSide, frame: LcpInboundFrame) {
        when (frame) {
            is LcpInboundFrame.Oversized -> answer(JsonRpcMessage.Failure(null, JsonRpcError(LcpErrorCode.MESSAGE_TOO_LARGE, "a message is at most ${LcpWire.MAX_MESSAGE_BYTES} bytes")))
            is LcpInboundFrame.Text -> when (val decoded = JsonRpcCodec.decode(frame.text)) {
                is JsonRpcDecoding.Decoded -> dispatch(side, decoded.message)
                is JsonRpcDecoding.Malformed -> decoded.answer?.let { answer(it) }
            }
        }
    }

    private suspend fun dispatch(side: LcpSide, message: JsonRpcMessage) {
        when (message) {
            is JsonRpcMessage.Request -> onRequest(side, message)
            is JsonRpcMessage.Notification -> onNotification(side, message)
            is JsonRpcMessage.Success -> calls.answer(message.id, message)
            is JsonRpcMessage.Failure -> message.id?.let { calls.answer(it, message) }
        }
    }

    private suspend fun onRequest(side: LcpSide, request: JsonRpcMessage.Request) {
        when (val verdict = admit(side, request)) {
            is InboundVerdict.Run -> begin(verdict.call)
            is InboundVerdict.Answer -> answer(JsonRpcMessage.Failure(request.id, verdict.error))
        }
    }

    private fun admit(side: LcpSide, request: JsonRpcMessage.Request): InboundVerdict {
        val method = LcpMethod.of(request.method)?.takeIf { it.direction.deliversTo(side) }
        val handler = method?.let(handlers::get)
        return when {
            method == null || handler == null -> refuse(LcpErrorCode.METHOD_NOT_FOUND, "no method ${request.method} towards the ${side.name.lowercase()}")
            !method.isRequest -> refuse(LcpErrorCode.INVALID_REQUEST, "${method.wire} is a notification and takes no id")
            calls.runningCount >= config.limits.maxConcurrentInbound -> refuse(LcpErrorCode.OVERLOADED, "too many requests in flight")
            else -> guarded(InboundCall(request.id, method, request.params, handler))
        }
    }

    private fun guarded(call: InboundCall): InboundVerdict = when (val admission = config.guard.incoming(call.method, call.params)) {
        is LcpAdmission.Admit -> InboundVerdict.Run(call.copy(params = admission.params))
        is LcpAdmission.Refuse -> InboundVerdict.Answer(admission.error)
    }

    private fun refuse(code: Int, message: String): InboundVerdict = InboundVerdict.Answer(JsonRpcError(code, message))

    private suspend fun begin(call: InboundCall) {
        if (calls.isRunning(call.id)) {
            answer(JsonRpcMessage.Failure(call.id, JsonRpcError(LcpErrorCode.INVALID_REQUEST, "id ${call.id} is already in flight")))
            return
        }
        // ATOMIC: the body runs even when a `$/cancel` lands before it starts, so every request is answered.
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            val response = execute(call)
            withContext(NonCancellable) { answer(response) }
        }
        calls.admit(call.id, job)
        job.invokeOnCompletion { calls.finish(call.id) }
    }

    private suspend fun execute(call: InboundCall): JsonRpcMessage {
        val outcome = try {
            JsonRpcMessage.Success(call.id, withTimeout(deadlineOf(call.method)) { call.handler.handle(call.params) })
        } catch (_: TimeoutCancellationException) {
            failure(call, LcpErrorCode.DEADLINE_EXCEEDED, "${call.method.wire} passed its deadline")
        } catch (e: LcpCallException) {
            JsonRpcMessage.Failure(call.id, e.error)
        } catch (e: CancellationException) {
            if (!calls.wasCancelledByRemote(call.id)) throw e
            failure(call, LcpErrorCode.REQUEST_CANCELLED, "${call.method.wire} was cancelled")
        } catch (e: IllegalArgumentException) {
            failure(call, LcpErrorCode.INVALID_PARAMS, e.message ?: "invalid params")
        } catch (e: Exception) {
            failure(call, LcpErrorCode.INTERNAL_ERROR, e.message ?: "the handler failed")
        }
        config.guard.completed(call.method, outcome is JsonRpcMessage.Success)
        return outcome
    }

    private fun failure(call: InboundCall, code: Int, message: String) = JsonRpcMessage.Failure(call.id, JsonRpcError(code, message))

    private suspend fun onNotification(side: LcpSide, notification: JsonRpcMessage.Notification) {
        val method = LcpMethod.of(notification.method)?.takeIf { it.direction.deliversTo(side) && !it.isRequest } ?: return
        if (method == LcpMethod.CANCEL) return cancel(notification.params)
        val handler = handlers[method] ?: return
        val admission = config.guard.incoming(method, notification.params) as? LcpAdmission.Admit ?: return
        notifications.send(handler to admission.params)
    }

    private fun cancel(params: JsonObject) {
        val id = runCatching { LcpCalls.CANCEL.decodeParams(params).id }.getOrNull() ?: return
        calls.cancel(id)
    }

    private suspend fun runNotification(handler: LcpHandler, params: JsonObject) {
        try {
            withTimeout(config.limits.defaultDeadline) { handler.handle(params) }
        } catch (_: TimeoutCancellationException) {
            // A notification has no one to answer; a slow handler only loses its own work.
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Likewise: a notification's failure is the handler's to report, never the reader's end.
        }
    }

    private suspend fun answer(message: JsonRpcMessage) = io.trySend(message)

    private fun deadlineOf(method: LcpMethod): Duration = method.deadline ?: config.limits.defaultDeadline

    private companion object {
        const val NOTIFICATION_QUEUE = 256
    }
}
