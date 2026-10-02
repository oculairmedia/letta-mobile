package com.letta.mobile.web.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
 * timeline run in a scope that ends with it, so a replaced port stops with its composition.
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
        val portScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val port = try {
            WebChatSessionPort(opener.open(agent), portScope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            value = WebChatLoad.Failed(failure.message ?: "Could not open the conversation")
            return@produceState
        }
        value = WebChatLoad.Ready(port)
        awaitDispose {
            port.close()
            portScope.cancel()
        }
    }
    return load
}
