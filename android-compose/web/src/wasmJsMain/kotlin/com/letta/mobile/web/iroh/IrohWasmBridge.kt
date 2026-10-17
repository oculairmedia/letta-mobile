@file:OptIn(ExperimentalWasmJsInterop::class)
// The js() bindings at the end read their parameters by name, which detekt cannot see.
@file:Suppress("UnusedParameter")

package com.letta.mobile.web.iroh

import com.letta.mobile.web.data.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await

internal class IrohWasmBridge private constructor(
    private val sessionId: Int,
) {
    suspend fun sendControl(payload: String) {
        callSendControl(sessionId, payload).await()
    }

    fun pollControl(): String? = callPollControl(sessionId)?.toString()

    fun pollStream(): String? = callPollStream(sessionId)?.toString()

    fun state(): String = callSessionState(sessionId).toString()

    fun error(): String? = callSessionError(sessionId)?.toString()

    suspend fun close() {
        callClose(sessionId).await()
    }

    /**
     * letta-mobile-ckmub.17.9: a canvas relay stream to this session's host, dialled from the
     * session's own endpoint on the relay's ALPN, so the host admits it for this session's identity.
     */
    suspend fun openCanvasRelay(): IrohWasmRelayStream =
        IrohWasmRelayStream(rejectedAsState("Could not open the canvas relay") { callOpenCanvasRelay(sessionId).await<JsNumber>().toInt() })

    companion object {
        /**
         * Dials [endpointUrl] (`iroh://<endpoint id>`). A rejection from the JS side (a JS `Error`)
         * comes back as an [IllegalStateException] with a readable message, never the raw JS value.
         */
        suspend fun connect(endpointUrl: String): IrohWasmBridge =
            IrohWasmBridge(rejectedAsState("Could not connect over Iroh") { callConnect(endpointUrl).await<JsNumber>().toInt() })
    }
}

/** One canvas relay stream of a session ([IrohWasmBridge.openCanvasRelay]): text frames both ways. */
internal class IrohWasmRelayStream(private val relayId: Int) {
    suspend fun send(payload: String) {
        callRelaySend(relayId, payload).await<JsAny?>()
    }

    fun poll(): String? = callRelayPoll(relayId)?.toString()

    fun state(): String = callRelayState(relayId).toString()

    fun error(): String? = callRelayError(relayId)?.toString()

    suspend fun close() {
        callRelayClose(relayId).await<JsAny?>()
    }
}

/** [block]'s result; a JS rejection becomes an [IllegalStateException] that says [what] failed and why. */
private suspend fun <T> rejectedAsState(what: String, block: suspend () -> T): T = runCatching { block() }.getOrElse { failure ->
    if (failure is CancellationException) throw failure
    throw IllegalStateException("$what: ${failure.displayMessage("unknown error")}", failure)
}

private fun callConnect(ticket: String): kotlin.js.Promise<JsNumber> =
    js("window.irohConnect(ticket)")

private fun callSendControl(sessionId: Int, payload: String): kotlin.js.Promise<JsAny?> =
    js("window.irohSendControl(sessionId, payload)")

private fun callPollControl(sessionId: Int): JsString? =
    js("window.irohPollControl(sessionId)")

private fun callPollStream(sessionId: Int): JsString? =
    js("window.irohPollStream(sessionId)")

private fun callSessionState(sessionId: Int): JsString =
    js("window.irohSessionState(sessionId)")

private fun callSessionError(sessionId: Int): JsString? =
    js("window.irohSessionError(sessionId)")

private fun callClose(sessionId: Int): kotlin.js.Promise<JsAny?> =
    js("window.irohClose(sessionId)")

private fun callOpenCanvasRelay(sessionId: Int): kotlin.js.Promise<JsNumber> =
    js("window.irohOpenCanvasRelay(sessionId)")

private fun callRelaySend(relayId: Int, payload: String): kotlin.js.Promise<JsAny?> =
    js("window.irohRelaySend(relayId, payload)")

private fun callRelayPoll(relayId: Int): JsString? =
    js("window.irohRelayPoll(relayId)")

private fun callRelayState(relayId: Int): JsString =
    js("window.irohRelayState(relayId)")

private fun callRelayError(relayId: Int): JsString? =
    js("window.irohRelayError(relayId)")

private fun callRelayClose(relayId: Int): kotlin.js.Promise<JsAny?> =
    js("window.irohRelayClose(relayId)")
