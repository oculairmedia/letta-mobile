@file:OptIn(ExperimentalWasmJsInterop::class)
// The js() bindings at the end read their parameters by name, which detekt cannot see.
@file:Suppress("UnusedParameter")

package com.letta.mobile.web.iroh

import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.web.data.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await

/** The JS glue's handle for one Iroh App Server session (`window.irohConnect`). */
internal value class IrohSessionHandle(val js: JsNumber)

/** The JS glue's handle for one canvas relay stream of a session (`window.irohOpenCanvasRelay`). */
internal value class IrohRelayHandle(val js: JsNumber)

/** One frame's text as it crosses the JS glue, either way. */
internal value class IrohWireFrame(val text: String) {
    val js: JsString get() = text.toJsString()

    companion object {
        fun of(js: JsString?): IrohWireFrame? = js?.let { IrohWireFrame(it.toString()) }
    }
}

/** Where a JS-side session or stream is, as the glue reports it. */
internal enum class IrohStreamState {
    CONNECTED,
    ERROR,

    /** Disconnected, closed, or unknown to the glue: no more frames will come. */
    ENDED,
    ;

    companion object {
        fun of(js: JsString): IrohStreamState = when (js.toString()) {
            "connected" -> CONNECTED
            "error" -> ERROR
            else -> ENDED
        }
    }
}

internal class IrohWasmBridge private constructor(
    private val session: IrohSessionHandle,
) {
    suspend fun sendControl(frame: IrohWireFrame) {
        callSendControl(session.js, frame.js).await<JsAny?>()
    }

    fun pollControl(): IrohWireFrame? = IrohWireFrame.of(callPollControl(session.js))

    fun pollStream(): IrohWireFrame? = IrohWireFrame.of(callPollStream(session.js))

    fun state(): IrohStreamState = IrohStreamState.of(callSessionState(session.js))

    fun error(): String? = callSessionError(session.js)?.toString()

    suspend fun close() {
        callClose(session.js).await<JsAny?>()
    }

    /**
     * letta-mobile-ckmub.17.9: a canvas relay stream to this session's host, dialled from the
     * session's own endpoint on the relay's ALPN, so the host admits it for this session's identity.
     */
    suspend fun openCanvasRelay(): IrohWasmRelayStream {
        val relay = rejectedAsState("Could not open the canvas relay") { callOpenCanvasRelay(session.js).await<JsNumber>() }
        return IrohWasmRelayStream(IrohRelayHandle(relay))
    }

    companion object {
        /**
         * Dials [address] by its endpoint id (through the relay; a browser has no direct UDP). A
         * rejection from the JS side (a JS `Error`) comes back as an [IllegalStateException] with a
         * readable message, never the raw JS value.
         */
        suspend fun connect(address: IrohServerAddress): IrohWasmBridge {
            val session = rejectedAsState("Could not connect over Iroh") { callConnect(address.endpointUrl.toJsString()).await<JsNumber>() }
            return IrohWasmBridge(IrohSessionHandle(session))
        }
    }
}

/** One canvas relay stream of a session ([IrohWasmBridge.openCanvasRelay]): text frames both ways. */
internal class IrohWasmRelayStream(private val relay: IrohRelayHandle) {
    suspend fun send(frame: IrohWireFrame) {
        callRelaySend(relay.js, frame.js).await<JsAny?>()
    }

    fun poll(): IrohWireFrame? = IrohWireFrame.of(callRelayPoll(relay.js))

    fun state(): IrohStreamState = IrohStreamState.of(callRelayState(relay.js))

    fun error(): String? = callRelayError(relay.js)?.toString()

    suspend fun close() {
        callRelayClose(relay.js).await<JsAny?>()
    }
}

/** [block]'s result; a JS rejection becomes an [IllegalStateException] that says [what] failed and why. */
private suspend fun <T> rejectedAsState(what: String, block: suspend () -> T): T = runCatching { block() }.getOrElse { failure ->
    if (failure is CancellationException) throw failure
    throw IllegalStateException("$what: ${failure.displayMessage("unknown error")}", failure)
}

private fun callConnect(ticket: JsString): kotlin.js.Promise<JsNumber> =
    js("window.irohConnect(ticket)")

private fun callSendControl(session: JsNumber, payload: JsString): kotlin.js.Promise<JsAny?> =
    js("window.irohSendControl(session, payload)")

private fun callPollControl(session: JsNumber): JsString? =
    js("window.irohPollControl(session)")

private fun callPollStream(session: JsNumber): JsString? =
    js("window.irohPollStream(session)")

private fun callSessionState(session: JsNumber): JsString =
    js("window.irohSessionState(session)")

private fun callSessionError(session: JsNumber): JsString? =
    js("window.irohSessionError(session)")

private fun callClose(session: JsNumber): kotlin.js.Promise<JsAny?> =
    js("window.irohClose(session)")

private fun callOpenCanvasRelay(session: JsNumber): kotlin.js.Promise<JsNumber> =
    js("window.irohOpenCanvasRelay(session)")

private fun callRelaySend(relay: JsNumber, payload: JsString): kotlin.js.Promise<JsAny?> =
    js("window.irohRelaySend(relay, payload)")

private fun callRelayPoll(relay: JsNumber): JsString? =
    js("window.irohRelayPoll(relay)")

private fun callRelayState(relay: JsNumber): JsString =
    js("window.irohRelayState(relay)")

private fun callRelayError(relay: JsNumber): JsString? =
    js("window.irohRelayError(relay)")

private fun callRelayClose(relay: JsNumber): kotlin.js.Promise<JsAny?> =
    js("window.irohRelayClose(relay)")
