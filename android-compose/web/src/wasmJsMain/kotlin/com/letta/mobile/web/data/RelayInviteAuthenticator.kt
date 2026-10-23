package com.letta.mobile.web.data

import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.InviteAuthRequest
import com.letta.mobile.data.transport.iroh.InviteAuthenticator
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * letta-mobile-wisqe S1b: the browser's invite dial. One Iroh session through the relay, keyed by
 * the identity it is given, carries a single auth exchange and is torn down. The request id only
 * has to be unique on that one-shot connection.
 */
internal class RelayInviteAuthenticator : InviteAuthenticator {
    override suspend fun authenticate(
        secretKey: ByteArray,
        request: InviteAuthRequest,
    ): AppServerInboundFrame.AuthResponse = supervisorScope {
        // The transport's pumps and the client's router are children of this exchange, so none
        // outlives it.
        val address = IrohServerAddress.parse(request.dialAddress)
        val transport = IrohWasmAppServerTransport.connect(address, this, secretKey.toHexString())
        try {
            transport.isConnected.first { it }
            DefaultAppServerClient(transport, parentScope = this).auth(
                AppServerCommand.Auth(
                    requestId = webRequestId(Random.nextLong().toString(), "pair", 1),
                    token = request.inviteToken,
                    capabilities = listOf(FRAME_PART_CAPABILITY),
                    peerName = request.peerName,
                ),
            )
        } finally {
            withContext(NonCancellable) { runCatching { transport.close() } }
            coroutineContext.cancelChildren()
        }
    }
}
