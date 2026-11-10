package com.letta.mobile.data.controller.extras.relay

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Runs one relayed call with the app's own tool of that name, refusing every tool it does not relay. */
fun interface AppToolRelayAnswerer {
    /** What the app's tool returned for [request], for the scope the host named. */
    suspend fun answer(request: AppToolCallRequest): AppToolCallResult
}

/**
 * The app's side of the app-tool relay: decodes a call its Iroh host relayed, hands it to the app's
 * [answerer] and encodes the answer by the call's id. The transport hands it only frames from the host
 * it dialed and signed in to, once that host negotiated the relay.
 *
 * @param answerer the app's answerer now; null while the app has none (the call is then refused).
 */
class AppToolRelayResponder(private val answerer: () -> AppToolRelayAnswerer?) {
    /** Whether [frame] is a relayed call. */
    fun isRequest(frame: JsonObject): Boolean = AppToolRelayFrames.typeOf(frame) == AppToolRelay.REQUEST_TYPE

    /** The answer frame to the call in [frame], or null when [frame] is not a call it can answer by id. */
    suspend fun answer(frame: JsonObject): String? {
        val request = AppToolRelayFrames.decodeRequest(frame) ?: return null
        return AppToolRelayFrames.encode(AppToolCallResponse(request.callId, resultOf(request)))
    }

    /** The app's answer to [request]; a tool that throws is an error answer, so the host never waits out its timeout. */
    private suspend fun resultOf(request: AppToolCallRequest): AppToolCallResult {
        val answered = runCatching { answerer()?.answer(request) }
        val failure = answered.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) return AppToolCallResult(failed(request.toolName, failure), isError = true)
        return answered.getOrNull() ?: AppToolCallResult(notHandled(request.toolName), isError = true)
    }

    /** The responder every Iroh connection of this app uses, answering with what the app [install]ed. */
    companion object {
        @Volatile
        private var installed: AppToolRelayAnswerer? = null

        /** Answers with the answerer the app installed; refuses every call before one is. */
        val Default: AppToolRelayResponder = AppToolRelayResponder { installed }

        /**
         * Makes [answerer] the one [Default] answers with, until the returned handle is closed (which
         * puts back the one it replaced, unless another was installed since).
         */
        fun install(answerer: AppToolRelayAnswerer): AutoCloseable {
            val previous = installed
            installed = answerer
            return AutoCloseable { if (installed === answerer) installed = previous }
        }

        private fun failed(toolName: String, failure: Throwable) =
            "The app failed to run $toolName: ${failure.message ?: failure::class.simpleName}"

        private fun notHandled(toolName: String) = "This app does not run ${toolName.ifEmpty { "that tool" }} for its host."
    }
}
