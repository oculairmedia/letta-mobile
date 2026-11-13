package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlin.time.Duration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport

/**
 * Reacts to `conversation_updated` pushes on [transport] (Meridian sends them after every
 * conversation write from any client) by calling [onChanged]. Shared by every
 * conversation list so Android and desktop follow other devices the same way; pair it with
 * [observeReconnectRefresh], since pushes sent while a client was offline are lost.
 */
suspend fun observeConversationUpdates(
    transport: IChannelTransport,
    onChanged: suspend (ServerFrame.ConversationUpdated) -> Unit,
) {
    transport.events.collect { frame ->
        if (frame is ServerFrame.ConversationUpdated) onChanged(frame)
    }
}

/** Typed push identities, shared by repository and gateway conversation lists. */
fun IChannelTransport.conversationChanges(): Flow<Pair<ConversationId, AgentId?>> = events.mapNotNull { frame ->
    (frame as? ServerFrame.ConversationUpdated)?.let {
        ConversationId(it.conversationId) to it.agentId?.takeIf(String::isNotBlank)?.let(::AgentId)
    }
}

/** [observeConversationInvalidations] without a coalescing window: each queued burst is read at once. */
suspend fun observeConversationInvalidations(
    updates: Flow<Pair<ConversationId, AgentId?>>,
    refresh: suspend (Map<ConversationId, AgentId?>) -> Unit,
): Unit = observeConversationInvalidations(updates, Duration.ZERO, refresh)

/**
 * Keeps dirty identities while a read is in flight. Queued bursts drain into one batch, so each
 * owning agent needs at most one refresh per batch. A push arriving during that read remains
 * queued for the next batch, including another push for the same conversation. A positive
 * [coalesceWindow] waits that long after the first push of a batch, so a burst spread over time
 * (a streaming turn's pushes) is read once.
 */
suspend fun observeConversationInvalidations(
    updates: Flow<Pair<ConversationId, AgentId?>>,
    coalesceWindow: Duration,
    refresh: suspend (Map<ConversationId, AgentId?>) -> Unit,
): Unit = coroutineScope {
    val wake = Channel<Unit>(Channel.CONFLATED)
    val mutex = Mutex()
    val dirty = mutableMapOf<ConversationId, AgentId?>()
    launch {
        try {
            updates.collect { (conversation, agent) ->
                mutex.withLock { dirty[conversation] = agent ?: dirty[conversation] }
                wake.trySend(Unit)
            }
        } finally {
            wake.close()
        }
    }
    wake.consumeEach {
        if (coalesceWindow.isPositive()) delay(coalesceWindow) else yield()
        val batch = mutex.withLock { dirty.toMap().also { dirty.clear() } }
        if (batch.isNotEmpty()) refresh(batch)
    }
}
