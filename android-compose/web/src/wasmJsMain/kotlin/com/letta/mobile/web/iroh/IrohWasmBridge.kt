@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.iroh

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

    companion object {
        suspend fun connect(ticket: String): IrohWasmBridge {
            val id = callConnect(ticket).await().toInt()
            return IrohWasmBridge(id)
        }
    }
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
