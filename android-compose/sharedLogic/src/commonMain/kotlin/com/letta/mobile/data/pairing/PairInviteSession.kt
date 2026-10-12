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
)

/**
 * Inviter-side state holder (letta-mobile-dpv7m.2): mint a scoped invite and
 * show it as a QR, notice when a new device appears (accepted), list paired
 * devices and pending invites, revoke either. Shared by Android and desktop;
 * the platform supplies [scope] (its ViewModel / screen scope) and [encoder].
 */
class PairInviteSession(
    private val repository: PairingAdminRepository,
    private val encoder: QrEncoder,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PairInviteUiState())
    val state: StateFlow<PairInviteUiState> = _state.asStateFlow()

    /** NodeIds known when the current invite was minted; a newcomer is the redeemer. */
    private var knownAtMint: Set<String> = emptySet()

    fun mint(request: PairInviteRequest) = run { _ ->
        // Snapshot who is already paired BEFORE minting, so only a newcomer reads as "accepted".
        knownAtMint = repository.pairedDevices().map { it.nodeId }.toSet()
        val invite = repository.mint(request)
        _state.update { it.copy(invite = invite, qr = encoder.encode(invite.pairLink), accepted = null) }
        reload()
    }

    /** Re-read devices and invites; call while an invite is on screen to detect acceptance. */
    fun refresh() = run { reload() }

    fun revokeDevice(nodeId: String) = run {
        repository.revokeDevice(nodeId)
        reload()
    }

    fun revokeInvite(inviteId: String) = run { state ->
        repository.revokeInvite(inviteId)
        if (state.invite?.inviteId == inviteId) _state.update { it.copy(invite = null, qr = null) }
        reload()
    }

    /** Drop the on-screen QR (e.g. the sheet closed); the invite itself expires on the host. */
    fun dismissInvite() {
        _state.update { it.copy(invite = null, qr = null, accepted = null) }
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
    }
}
