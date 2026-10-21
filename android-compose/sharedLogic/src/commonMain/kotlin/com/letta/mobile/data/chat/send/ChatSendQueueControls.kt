package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a chat screen can see and do with the send queue. Every host (Android,
 * desktop) binds this one object; the queue logic stays in [QueuedSendDriver].
 */
class ChatSendQueueControls internal constructor(
    private val scope: CoroutineScope,
    private val driver: QueuedSendDriver,
    /** Every conversation's queued messages, in run order. */
    val state: StateFlow<SendQueues>,
) {
    /** Drops one queued message; it never reached the server, so nothing else needs undoing. */
    fun cancel(id: QueuedSendId): Job = scope.launch { driver.cancel(id) }

    /** Aborts the running turn and runs [id] next, ahead of anything queued before it. */
    fun sendNow(id: QueuedSendId): Job = scope.launch { driver.sendNow(id) }

    /** Releases a queue a Stop (or a disconnect) paused. */
    fun resume(conversationId: QueueConversationId): Job = scope.launch { driver.resume(conversationId) }
}

/** The coordinator's turn state as [QueuedSendDriver] sees it: its lock, its bridge, its dispatch. */
internal class CoordinatorQueueTurns(
    private val lock: Mutex,
    private val bridge: WsChatBridge,
    private val dispatchSend: suspend (QueuedChatSend, Boolean) -> Boolean,
) : QueuedSendTurns {
    override suspend fun <T> serialized(block: suspend () -> T): T = lock.withLock { block() }

    override fun hasActiveTurn(conversationId: QueueConversationId): Boolean =
        bridge.hasActiveChatTurn(conversationId.value)

    override fun abortTurn(conversationId: QueueConversationId): Boolean = bridge.cancel(conversationId.value)

    override suspend fun dispatch(item: QueuedChatSend, clearComposer: Boolean): Boolean = dispatchSend(item, clearComposer)

    /** Connection readiness belongs to the same bridge adapter used for queue ownership. */
    suspend fun ensureConnected(config: LettaConfig, clientVersion: () -> String): Boolean {
        if (bridge.isConnected()) return true
        runCatching {
            bridge.connect(
                baseShimUrl = config.serverUrl,
                token = config.accessToken.orEmpty(),
                deviceId = "android-letta-mobile",
                clientVersion = clientVersion(),
            )
        }.onFailure { error ->
            Telemetry.error("AdminChatVM", "ws.connect.failed", error)
            return false
        }
        return withTimeoutOrNull(CONNECT_WAIT_MS.milliseconds) {
            bridge.awaitConnected()
            true
        } ?: false
    }

    private companion object {
        const val CONNECT_WAIT_MS = 1_500L
    }
}
