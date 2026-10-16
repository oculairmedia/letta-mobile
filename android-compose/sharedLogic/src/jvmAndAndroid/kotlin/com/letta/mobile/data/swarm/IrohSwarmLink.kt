package com.letta.mobile.data.swarm

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.plus
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-jvctq: the swarm link over Iroh, as the paired device [profile] describes. Each
 * [withSession] binds an endpoint with the device's PERSISTENT key (the NodeId the host paired),
 * dials the stored host the way every Iroh client does ([IrohAppServerTransportAdapter] ->
 * [DefaultAppServerClient]), authenticates with a blank token (a paired device is known by its
 * NodeId), and hands the shared stack ([AppServerSwarmSession]) to the block. It asks for no
 * capability beyond `frame_part` framing: what the device may do is the grant its invite carried.
 */
class IrohSwarmLink(
    private val profile: SwarmDeviceProfile,
    private val clientInfo: AppServerRuntimeStartClientInfo =
        AppServerRuntimeStartClientInfo(name = AppServerSwarmSession.SWARM_CLIENT_NAME, version = "1"),
    private val dialTimeout: Duration = 30.seconds,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : SwarmLink {
    override suspend fun <T> withSession(block: suspend (SwarmSession) -> T): LinkResult<T> {
        val host = storedHost() ?: return LinkResult.Failed(LinkFailure.NotPaired)
        val key = profile.identity.loadOrCreate()
        return supervisorScope {
            // Transport readers are supervised children of this scope: a reader failing at teardown
            // cannot clobber the block's result, and they are cancelled before returning.
            val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = key))
            try {
                when (val dialled = dial(endpoint, host.dialAddress, this + Dispatchers.Default)) {
                    is Dialled.Failed -> LinkResult.Failed(dialled.failure)
                    is Dialled.Ready -> {
                        val session = AppServerSwarmSession(dialled.client, this + Dispatchers.Default, clientInfo)
                        try {
                            LinkResult.Ok(block(session))
                        } finally {
                            session.close()
                            runCatching { dialled.transport.close() }
                        }
                    }
                }
            } finally {
                coroutineContext.cancelChildren()
                runCatching { endpoint.close() }
            }
        }
    }

    private suspend fun storedHost(): PairedHostRecord? = try {
        profile.host()
    } catch (expected: SerializationException) {
        // An unreadable host file is no pairing: fail closed, the user re-pairs.
        null
    }

    @Suppress("TooGenericExceptionCaught") // Native Iroh dial errors share no type; any of them means unreachable.
    private suspend fun dial(endpoint: Endpoint, address: String, scope: CoroutineScope): Dialled = try {
        withTimeout(dialTimeout) {
            endpoint.online()
            val transport = IrohAppServerTransportAdapter(endpoint)
                .createTransport(endpoint = AppServerEndpoint(scheme = "iroh", address = address), scope = scope)
                as? IrohAppServerTransport ?: error("Iroh adapter returned a non-Iroh transport")
            transport.awaitConnectionReady()
            authenticate(transport)
        }
    } catch (timeout: TimeoutCancellationException) {
        Dialled.Failed(LinkFailure.Unreachable("timed out after $dialTimeout"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Dialled.Failed(LinkFailure.Unreachable(error::class.simpleName ?: "dial failed"))
    }

    private suspend fun authenticate(transport: IrohAppServerTransport): Dialled {
        val client = DefaultAppServerClient(transport, requestTimeoutMs = requestTimeoutMs)
        val auth = client.auth(
            AppServerCommand.Auth(
                requestId = "swarm-auth-${UUID.randomUUID()}",
                token = "",
                capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
            ),
        )
        if (auth.success) return Dialled.Ready(client, transport)
        runCatching { transport.close() }
        return Dialled.Failed(LinkFailure.Denied(auth.error ?: "denied"))
    }

    private sealed interface Dialled {
        class Ready(val client: DefaultAppServerClient, val transport: IrohAppServerTransport) : Dialled

        class Failed(val failure: LinkFailure) : Dialled
    }

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    }
}
