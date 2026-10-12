package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.ScannedInvite
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.util.Telemetry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.plus
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** One invite presentation: where to dial and what to say (the token is secret). */
class InviteAuthRequest(val dialAddress: String, val inviteToken: String, val peerName: String) {
    override fun toString(): String = "InviteAuthRequest(dial=$dialAddress, <redacted>)"
}

/** Network seam: dial with [secretKey] and send the auth frame; returns the host's answer. */
fun interface InviteAuthenticator {
    suspend fun authenticate(secretKey: ByteArray, request: InviteAuthRequest): AppServerInboundFrame.AuthResponse
}

/**
 * Presents a scanned invite to its host over Iroh (letta-mobile-dpv7m.2).
 *
 * The device dials with its PERSISTENT identity ([secretKeyStore] — the same
 * key the chat session uses), so the NodeId the host binds is the one that
 * reconnects afterwards. The dial address is derived from the link's hint but
 * must name the envelope's NodeId ([PairingDialAddress]); QUIC then proves the
 * host holds that key, so a tampered hint can only fail, never mis-pair.
 */
class IrohPairingRedeemer(
    private val secretKeyStore: IrohSecretKeyStore,
    private val authenticator: InviteAuthenticator = LiveInviteAuthenticator(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PairingRedeemer {
    override suspend fun redeem(invite: ScannedInvite, deviceName: String): RedeemOutcome {
        val dial = PairingDialAddress.resolve(invite.nodeId, invite.dial)
            ?: return RedeemOutcome.Failed(PairingError.HOST_MISMATCH)
        val request = InviteAuthRequest(dial, invite.inviteToken, deviceName)
        val response = try {
            withTimeout(timeoutMs) { authenticator.authenticate(secretKeyStore.loadOrCreate(), request) }
        } catch (timeout: TimeoutCancellationException) {
            Telemetry.event("IrohPairing", "redeem.timeout", "host" to invite.hostFingerprint)
            return RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Telemetry.event("IrohPairing", "redeem.unreachable", "host" to invite.hostFingerprint, "class" to error::class.simpleName)
            return RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
        }
        return outcomeOf(response, PairedHost(invite.nodeId, dial))
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** Map the host's fixed reason labels (never secret-derived) to user-facing errors. */
        fun outcomeOf(response: AppServerInboundFrame.AuthResponse, host: PairedHost): RedeemOutcome {
            if (response.success) return RedeemOutcome.Paired(host, alreadyPaired = response.alreadyPaired == true)
            val error = when (response.error) {
                "invite_expired" -> PairingError.EXPIRED
                "invite_invalid" -> PairingError.INVITE_INVALID
                "rate_limited" -> PairingError.RATE_LIMITED
                else -> PairingError.DENIED
            }
            Telemetry.event("IrohPairing", "redeem.rejected", "reason" to error.name)
            return RedeemOutcome.Failed(error)
        }
    }
}

/**
 * Resolves the address to dial for an invite: the envelope's NodeId plus the
 * link's reachability hint, refusing any hint that names a different node.
 */
object PairingDialAddress {
    private val HOST_PORTS = Regex("^[^\\s/@,]+:\\d{1,5}(,[^\\s/@,]+:\\d{1,5})*$")

    /** Null when the hint names another host (tampered or mismatched link). */
    fun resolve(nodeId: String, hint: String?): String? {
        val dial = hint?.trim().orEmpty()
        return when {
            dial.isEmpty() -> nodeId
            HOST_PORTS.matches(dial) -> "$nodeId@$dial"
            else -> dial.takeIf { namesNode(it, nodeId) }
        }
    }

    /** `<node>@host:port` is compared textually; anything else must parse as a ticket for that node. */
    private fun namesNode(address: String, nodeId: String): Boolean {
        val named = if ('@' in address) {
            address.substringBefore('@')
        } else {
            runCatching { IrohDiagnostics.endpointIdHex(IrohAppServerTransportAdapter.parseIrohAddress(address).id()) }.getOrNull()
        }
        return named.equals(nodeId, ignoreCase = true)
    }
}

/** Real Iroh dial + auth, torn down after the single exchange. */
class LiveInviteAuthenticator : InviteAuthenticator {
    override suspend fun authenticate(
        secretKey: ByteArray,
        request: InviteAuthRequest,
    ): AppServerInboundFrame.AuthResponse = supervisorScope {
        // Transport readers are supervised children: the teardown ConnectionLost
        // cannot fail the exchange, and they are cancelled before returning.
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = secretKey))
        try {
            endpoint.online()
            val transport = IrohAppServerTransportAdapter(endpoint).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = request.dialAddress),
                scope = this + Dispatchers.Default,
            ) as IrohAppServerTransport
            transport.awaitConnectionReady()
            DefaultAppServerClient(transport).auth(
                AppServerCommand.Auth(
                    requestId = "pair-${UUID.randomUUID()}",
                    token = request.inviteToken,
                    capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                    peerName = request.peerName,
                ),
            ).also { runCatching { transport.close() } }
        } finally {
            coroutineContext.cancelChildren()
            runCatching { endpoint.close() }
        }
    }
}
