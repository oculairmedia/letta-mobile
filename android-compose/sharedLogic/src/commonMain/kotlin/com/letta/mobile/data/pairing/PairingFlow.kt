package com.letta.mobile.data.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every way pairing can fail, as the user should hear it (letta-mobile-dpv7m.2).
 * Never carries the secret, the raw QR value, or server error text.
 */
enum class PairingError {
    NOT_A_PAIRING_CODE,
    UNSUPPORTED_VERSION,
    MALFORMED,
    EXPIRED,
    HOST_UNREACHABLE,

    /** The link's dial address names a different host than the signed envelope. */
    HOST_MISMATCH,

    /** Unknown, already used, or revoked invite. */
    INVITE_INVALID,
    DENIED,
    RATE_LIMITED,

    /** Paired, but the host could not be saved on this device. */
    SAVE_FAILED,
}

/** A host this device is paired with; [serverUrl] is what the session layer dials. */
data class PairedHost(val nodeId: String, val dialAddress: String) {
    val serverUrl: String get() = "iroh://$dialAddress"
}

/** Result of presenting an invite to its host. */
sealed interface RedeemOutcome {
    /** [alreadyPaired]: this device was paired before; the invite was left unused. */
    data class Paired(val host: PairedHost, val alreadyPaired: Boolean) : RedeemOutcome

    data class Failed(val error: PairingError) : RedeemOutcome
}

/**
 * Platform port: dial [ScannedInvite.nodeId] with this device's PERSISTENT Iroh
 * identity and present the invite in the auth frame. The binding the host
 * records is this device's NodeId, so the identity must be the same one the
 * chat session dials with afterwards.
 */
fun interface PairingRedeemer {
    suspend fun redeem(invite: ScannedInvite, deviceName: String): RedeemOutcome
}

/** Platform port: persist the host (secure settings) and make it the active backend. */
fun interface PairedHostStore {
    suspend fun saveAndActivate(host: PairedHost)
}

sealed interface PairingState {
    data object Idle : PairingState

    /** Talking to the host; [hostFingerprint] lets the user compare with the inviter's screen. */
    data class Pairing(val hostFingerprint: String) : PairingState

    data class Paired(val host: PairedHost, val alreadyPaired: Boolean) : PairingState

    data class Failed(val error: PairingError) : PairingState
}

/**
 * The scan -> pair -> persist -> connect state machine shared by Android and
 * desktop (letta-mobile-dpv7m.2). Platforms only supply the scanned text (camera,
 * deep link, paste) and bind the two ports; on [PairingState.Paired] they open
 * chat on the now-active host.
 *
 * Order matters for safety: format and expiry are checked locally first (no
 * network for a stale or foreign code); the host is persisted only after it
 * accepted the invite; nothing secret is kept — the invite is one-time and the
 * device authenticates by NodeId from then on.
 */
class PairingFlow(
    private val redeemer: PairingRedeemer,
    private val store: PairedHostStore,
    private val deviceName: String,
    private val nowMs: () -> Long,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /** Handle one scanned/opened value. Ignored while a pairing is already in flight. */
    suspend fun submit(raw: String): PairingState {
        if (_state.value is PairingState.Pairing) return _state.value
        val invite = when (val parsed = ScannedInviteParser.parse(raw)) {
            is ParsedInvite.Invalid -> return fail(parsed.error.toPairingError())
            is ParsedInvite.Valid -> parsed.invite
        }
        if (nowMs() >= invite.expiresAtMs + CLOCK_SKEW_ALLOWANCE_MS) return fail(PairingError.EXPIRED)
        _state.value = PairingState.Pairing(invite.hostFingerprint)
        return when (val outcome = redeemSafely(invite)) {
            is RedeemOutcome.Failed -> fail(outcome.error)
            is RedeemOutcome.Paired -> persist(outcome)
        }
    }

    /** Back to idle, e.g. "Scan again". */
    fun reset() {
        _state.value = PairingState.Idle
    }

    private suspend fun redeemSafely(invite: ScannedInvite): RedeemOutcome = try {
        redeemer.redeem(invite, deviceName)
    } catch (cancelled: CancellationException) {
        _state.value = PairingState.Idle
        throw cancelled
    } catch (_: Exception) {
        RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
    }

    private suspend fun persist(outcome: RedeemOutcome.Paired): PairingState = try {
        store.saveAndActivate(outcome.host)
        PairingState.Paired(outcome.host, outcome.alreadyPaired).also { _state.value = it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(PairingError.SAVE_FAILED)
    }

    private fun fail(error: PairingError): PairingState = PairingState.Failed(error).also { _state.value = it }

    private fun InviteFormatError.toPairingError(): PairingError = when (this) {
        InviteFormatError.NOT_A_PAIRING_CODE -> PairingError.NOT_A_PAIRING_CODE
        InviteFormatError.UNSUPPORTED_VERSION -> PairingError.UNSUPPORTED_VERSION
        InviteFormatError.MALFORMED -> PairingError.MALFORMED
    }

    companion object {
        /**
         * Bounded client clock-skew allowance (protocol §6.2 / §11): a phone
         * whose clock runs slightly fast is not told a fresh code expired. The
         * host's expiry check is authoritative either way.
         */
        const val CLOCK_SKEW_ALLOWANCE_MS = 30_000L
    }
}
