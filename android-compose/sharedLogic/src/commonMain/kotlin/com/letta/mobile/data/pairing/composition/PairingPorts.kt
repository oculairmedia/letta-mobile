package com.letta.mobile.data.pairing.composition

import com.letta.mobile.data.pairing.PairInviteSession
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.PairingAdminRepository
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How this device reaches a host to present an invite. Shared code
 * implements it per transport — `IrohPairingNetwork` on JVM and Android — so which redeemer
 * runs and how its outcomes map are never a platform decision; a platform passes only the
 * network seam the transport is built over.
 */
interface PairingNetwork {
    /** A redeemer that dials with the key [identityKey] loads: the identity the chat session uses. */
    fun redeemer(identityKey: suspend () -> ByteArray): PairingRedeemer

    /** The NodeId [secretKey] is known by on this network. */
    fun nodeIdOf(secretKey: ByteArray): String
}

/**
 * The services pairing needs that a platform physically owns. Everything
 * else — the join flow, the invite session, this device's NodeId — is built from these by
 * [PairingComposition], identically on every client.
 */
interface PairingPorts {
    /** Loads, or on first use creates, this device's PERSISTENT identity key (the one chat dials with). */
    suspend fun loadIdentityKey(): ByteArray

    /** The transport invites are presented over. */
    val network: PairingNetwork

    /** Saves a newly paired host and makes it the active backend. */
    val hostStore: PairedHostStore

    /** The name the host lists this device under. */
    val deviceName: String

    /** admin_rpc to the connected host; null when this client cannot reach one (inviting needs it). */
    val adminRpc: AdminRpcInvoker?

    /** Renders invite links as QR codes. */
    val qrEncoder: QrEncoder

    /** Wall-clock milliseconds (invite expiry, countdowns). */
    fun nowMs(): Long
}

/** The pairing composition every client shares: one place builds it from [ports]. */
class PairingComposition(private val ports: PairingPorts) {
    /** The port's admin_rpc; null hides inviting. */
    val adminRpc: AdminRpcInvoker? get() = ports.adminRpc

    /** The port's QR encoder. */
    val qrEncoder: QrEncoder get() = ports.qrEncoder

    /** The port's clock. */
    fun nowMs(): Long = ports.nowMs()

    /** A fresh scan -> confirm -> pair -> persist flow over this device's identity and host store. */
    fun joinFlow(): PairingFlow = PairingFlow(
        redeemer = ports.network.redeemer(ports::loadIdentityKey),
        store = ports.hostStore,
        deviceName = ports.deviceName,
        nowMs = ports::nowMs,
    )

    /** "Pair a device": the host mints, lists and revokes invites over [rpc]. */
    fun inviteSession(rpc: AdminRpcInvoker, scope: CoroutineScope): PairInviteSession =
        PairInviteSession(PairingAdminRepository(rpc), ports.qrEncoder, scope, ports::nowMs)

    /** This device's own NodeId (groups "This device" in the paired list); null when it cannot be derived. */
    suspend fun selfNodeId(): String? = try {
        withContext(Dispatchers.Default) { ports.network.nodeIdOf(ports.loadIdentityKey()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
