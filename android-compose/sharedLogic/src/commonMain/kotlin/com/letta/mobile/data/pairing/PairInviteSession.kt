package com.letta.mobile.data.pairing

import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A square QR module grid; `dark(row, col)` is true for a dark module. */
class QrModules(val size: Int, private val dark: (row: Int, col: Int) -> Boolean) {
    fun isDark(row: Int, col: Int): Boolean = dark(row, col)
}

/** Platform port: render text as a QR grid (zxing on JVM/Android); throws if it cannot fit. */
fun interface QrEncoder {
    fun encode(content: String): QrModules
}

/** What the inviter's screen shows. */
data class PairInviteUiState(
    val busy: Boolean = false,
    /** User-facing error (host message or a fixed explanation); never contains a secret. */
    val error: String? = null,
    val invite: MintedPairInvite? = null,
    val qr: QrModules? = null,
    val devices: List<PairedDevice> = emptyList(),
    val pending: List<PendingInvite> = emptyList(),
    /** The device that redeemed the invite on screen, once it shows up. */
    val accepted: PairedDevice? = null,
    /** The scope the last code was minted with; "New code" reuses it. */
    val lastRequest: PairInviteRequest? = null,
    /** The inviter withdrew the on-screen code. */
    val cancelled: Boolean = false,
    /** Devices whose revocation was sent but not yet confirmed by the host ("Revoking…"). */
    val revoking: Set<String> = emptySet(),
    /** Pending invites whose cancellation was sent but not yet confirmed. */
    val cancelling: Set<String> = emptySet(),
    /** This device's own NodeId, when the platform knows it (groups "This device"). */
    val selfNodeId: String? = null,
) {
    val groups: PairedDeviceGroups get() = PairedDeviceGroups.of(devices, selfNodeId)
}

/**
 * Inviter-side state holder (letta-mobile-dpv7m.2, UX pass dpv7m.5): mint a
 * scoped invite and show it as a QR, notice when a new device appears
 * (accepted), list paired devices and pending invites, revoke either. Shared
 * by Android and desktop; the platform supplies [scope] (its ViewModel /
 * screen scope), [encoder], and [clock] (a test clock in tests).
 *
 * Revocations are tracked per item: a row reads "Revoking…" until the host
 * answers, and never disappears on optimism alone.
 */
class PairInviteSession(
    private val repository: PairingAdminRepository,
    private val encoder: QrEncoder,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(PairInviteUiState())
    val state: StateFlow<PairInviteUiState> = _state.asStateFlow()

    /** NodeIds known when the current invite was minted; a newcomer is the redeemer. */
    private var knownAtMint: Set<String> = emptySet()

    /** The card's phase right now (pure function of state + [clock]). */
    fun phase(): InvitePhase = InvitePhases.of(_state.value, clock())

    fun mint(request: PairInviteRequest) = run { _ -> mintNow(request) }

    /**
     * "New code": mint again with the last scope. A still-live previous code is
     * withdrawn first so two redeemable codes never exist at once.
     */
    fun newCode() = run { state ->
        val request = state.lastRequest ?: return@run
        state.invite?.takeIf { it.expiresAtMs > clock() }?.let { repository.revokeInvite(it.inviteId) }
        mintNow(request)
    }

    /** Re-read devices and invites; call while an invite is on screen to detect acceptance. */
    fun refresh() = run { reload() }

    /** The platform's own NodeId (null when unknown); only used to group the list. */
    fun identifySelf(nodeId: String?) {
        _state.update { it.copy(selfNodeId = nodeId?.lowercase()) }
    }

    fun revokeDevice(nodeId: String) = tracked(Tracked.DEVICE, nodeId, REVOKE_FAILED) { repository.revokeDevice(nodeId) }

    /** "Revoke all other devices": every paired device except this one. Needs this device to be known. */
    fun revokeAllOthers() {
        val groups = _state.value.groups
        if (!groups.canRevokeAllOthers) return
        groups.others.forEach { revokeDevice(it.nodeId) }
    }

    /** Cancel a pending invite (the on-screen one or one from the list). */
    fun revokeInvite(inviteId: String) = tracked(Tracked.INVITE, inviteId, CANCEL_FAILED) {
        repository.revokeInvite(inviteId).also { revoked ->
            if (revoked && _state.value.invite?.inviteId == inviteId) {
                _state.update { it.copy(invite = null, qr = null, cancelled = true) }
            }
        }
    }

    /** Back to Configuring (e.g. "Change access", or the sheet closed); an on-screen invite expires on the host. */
    fun dismissInvite() {
        _state.update { it.copy(invite = null, qr = null, accepted = null, cancelled = false) }
    }

    private suspend fun mintNow(request: PairInviteRequest) {
        // Snapshot who is already paired BEFORE minting, so only a newcomer reads as "accepted".
        knownAtMint = repository.pairedDevices().map { it.nodeId }.toSet()
        val invite = repository.mint(request)
        _state.update {
            it.copy(invite = invite, qr = encoder.encode(invite.pairLink), accepted = null, lastRequest = request, cancelled = false)
        }
        reload()
    }

    private suspend fun reload() {
        val devices = repository.pairedDevices()
        val pending = repository.pendingInvites()
        _state.update { current ->
            val newcomer = current.invite?.let { devices.firstOrNull { it.nodeId !in knownAtMint } }
            current.copy(
                devices = devices,
                pending = pending,
                accepted = newcomer ?: current.accepted,
                invite = if (newcomer != null) null else current.invite,
                qr = if (newcomer != null) null else current.qr,
            )
        }
    }

    private enum class Tracked { DEVICE, INVITE }

    private fun PairInviteUiState.withTracked(kind: Tracked, id: String, on: Boolean): PairInviteUiState = when (kind) {
        Tracked.DEVICE -> copy(revoking = if (on) revoking + id else revoking - id)
        Tracked.INVITE -> copy(cancelling = if (on) cancelling + id else cancelling - id)
    }

    private fun isTracked(kind: Tracked, id: String): Boolean = when (kind) {
        Tracked.DEVICE -> id in _state.value.revoking
        Tracked.INVITE -> id in _state.value.cancelling
    }

    /** Run one revoke/cancel independently of [run]'s busy gate, marking [id] until the host answers. */
    private fun tracked(kind: Tracked, id: String, failure: String, action: suspend () -> Boolean) {
        if (isTracked(kind, id)) return
        _state.update { it.withTracked(kind, id, on = true).copy(error = null) }
        scope.launch {
            try {
                if (!action()) _state.update { it.copy(error = failure) }
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = userMessage(error)) }
            } finally {
                _state.update { it.withTracked(kind, id, on = false) }
            }
        }
    }

    private fun userMessage(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            "forbidden" in message -> NOT_ALLOWED
            AdminRpcErrors.isUnknownMethod(message) -> HOST_TOO_OLD
            message.isNotBlank() -> message
            else -> GENERIC_FAILURE
        }
    }

    private fun run(action: suspend (PairInviteUiState) -> Unit) {
        val snapshot = _state.value
        if (snapshot.busy) return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                action(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = userMessage(error)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        const val NOT_ALLOWED = "This device is not allowed to invite others. Ask the host's administrator."
        const val HOST_TOO_OLD = "This host does not support scan-to-pair yet. Update the Meridian host."
        const val GENERIC_FAILURE = "The host could not complete the request."
        const val REVOKE_FAILED = "The host did not confirm the revocation. The device may still have access; try again."
        const val CANCEL_FAILED = "The host did not confirm the cancellation. The code may still work until it expires."
    }
}
