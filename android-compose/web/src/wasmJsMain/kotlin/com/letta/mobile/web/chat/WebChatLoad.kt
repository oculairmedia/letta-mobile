package com.letta.mobile.web.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebConnectionState
import kotlinx.coroutines.CancellationException

/** Where the selected agent's conversation is on its way to the page. */
sealed interface WebChatLoad {
    /** No agent selected, or no live connection. */
    data object Idle : WebChatLoad

    data object Opening : WebChatLoad

    data class Ready(val port: WebChatSessionPort) : WebChatLoad

    data class Failed(val message: String) : WebChatLoad
}

/** Opens the conversation for an agent on the live connection. */
fun interface WebChatOpener {
    suspend fun open(agent: AgentItemState): WebChatBinding
}

/**
 * The selected agent's [WebChatSessionPort], rebuilt whenever the agent or the connection changes
 * (a reconnect is a new App Server session, so the old port's timeline is stale). The port and its
 * timeline run in the producer's scope, so a replaced port stops with it.
 */
@Composable
internal fun rememberWebChatLoad(
    opener: WebChatOpener,
    agent: AgentItemState?,
    connection: WebConnectionState,
): WebChatLoad {
    val load by produceState<WebChatLoad>(WebChatLoad.Idle, opener, agent?.id, connection) {
        if (agent == null || connection !is WebConnectionState.Connected) {
            value = WebChatLoad.Idle
            return@produceState
        }
        value = WebChatLoad.Opening
        // This producer's own scope: it ends when the agent or connection changes or the page
        // leaves the composition, and the port's timeline ends with it.
        val port = try {
            WebChatSessionPort(opener.open(agent), this)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            value = WebChatLoad.Failed(failure.message ?: "Could not open the conversation")
            return@produceState
        }
        value = WebChatLoad.Ready(port)
        awaitDispose { port.close() }
    }
    return load
}
