package com.letta.mobile.data.repository

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport

/**
 * Reacts to `conversation_updated` pushes on [transport] (Meridian sends them after every
 * conversation write from any client, letta-mobile-lks7m) by calling [onChanged]. Shared by every
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
