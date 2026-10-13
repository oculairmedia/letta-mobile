@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.iroh

import com.letta.mobile.web.data.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await

internal class IrohWasmBridge private constructor(
    private val sessionId: Int,
) {
    /** With [allowFrameParts], a payload over 1 MiB is split into `frame_part`s instead of rejected. */
    suspend fun sendControl(payload: String, allowFrameParts: Boolean) {
        callSendControl(sessionId, payload, allowFrameParts).await()
    }

    fun pollControl(): String? = callPollControl(sessionId)?.toString()

    fun pollStream(): String? = callPollStream(sessionId)?.toString()

    fun state(): String = callSessionState(sessionId).toString()

    fun error(): String? = callSessionError(sessionId)?.toString()

    suspend fun close() {
        callClose(sessionId).await()
    }

    companion object {
        /**
         * Dials [endpointUrl] (`iroh://<endpoint id>`). A rejection from the JS side (a JS `Error`)
         * comes back as an [IllegalStateException] with a readable message, never the raw JS value.
         */
        suspend fun connect(endpointUrl: String): IrohWasmBridge {
            val id = try {
                callConnect(endpointUrl).await().toInt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw IllegalStateException("Could not connect over Iroh: ${failure.displayMessage("unknown error")}", failure)
            }
            return IrohWasmBridge(id)
        }
    }
}

private fun callConnect(ticket: String): kotlin.js.Promise<JsNumber> =
    js("window.irohConnect(ticket)")

private fun callSendControl(sessionId: Int, payload: String, allowFrameParts: Boolean): kotlin.js.Promise<JsAny?> =
    js("window.irohSendControl(sessionId, payload, allowFrameParts)")

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
