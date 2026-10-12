package com.letta.mobile.data.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

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

/** Human-checkable host ids (dpv7m.5): first and last six characters, never the whole key in primary UI. */
object HostIds {
    private const val EDGE_CHARS = 6

    fun short(nodeId: String): String =
        if (nodeId.length <= EDGE_CHARS * 2) nodeId else "${nodeId.take(EDGE_CHARS)}…${nodeId.takeLast(EDGE_CHARS)}"
}

sealed interface PairingState {
    data object Idle : PairingState

    /**
     * A valid code was read but NOTHING has been dialled yet (dpv7m.5): the user
     * must confirm "Connect to <host>?" first. Every entry — camera, paste, and
     * especially meridian:// / letta:// deep links — stops here.
     */
    data class ConfirmHost(val nodeId: String, val expiresAtMs: Long) : PairingState {
        val hostShortId: String get() = HostIds.short(nodeId)
    }

    /** Verifying with the host; [hostFingerprint] lets the user compare with the inviter's screen. */
    data class Pairing(val hostFingerprint: String) : PairingState

    /** [deviceName] is the name this device registered with the host. */
    data class Paired(val host: PairedHost, val alreadyPaired: Boolean, val deviceName: String = "") : PairingState

    /** [retryable]: the same (unused) code can be tried again — only when the host was unreachable. */
    data class Failed(val error: PairingError, val retryable: Boolean = false) : PairingState
}

/**
 * The scan -> confirm -> pair -> persist -> connect state machine shared by
 * Android and desktop (letta-mobile-dpv7m.2, trust prompt dpv7m.5). Platforms
 * only supply the scanned text (camera, deep link, paste) and bind the two
 * ports; on [PairingState.Paired] they open chat on the now-active host.
 *
 * Order matters for safety: format and expiry are checked locally first (no
 * network for a stale or foreign code); the user confirms the host before any
 * dial; the host is persisted only after it accepted the invite; nothing
 * secret is kept — the invite is one-time and the device authenticates by
 * NodeId from then on. Failure never leaves a saved host (fail closed).
 */
class PairingFlow(
    private val redeemer: PairingRedeemer,
    private val store: PairedHostStore,
    private val deviceName: String,
    private val nowMs: () -> Long,
    private val verifyTimeoutMs: Long = VERIFY_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /** The parsed invite awaiting confirmation, or kept for a retry after an unreachable host. */
    private var candidate: ScannedInvite? = null

    /** Bumped by every confirm/cancel so a cancelled verification cannot persist late. */
    private var attempt = 0

    /**
     * Handle one scanned/opened/pasted value: parse and check expiry locally, then
     * stop at [PairingState.ConfirmHost]. Ignored while a code is being confirmed
     * or verified.
     */
    fun submit(raw: String): PairingState {
        val current = _state.value
        if (current is PairingState.ConfirmHost || current is PairingState.Pairing) return current
        val invite = when (val parsed = ScannedInviteParser.parse(raw)) {
            is ParsedInvite.Invalid -> return fail(parsed.error.toPairingError())
            is ParsedInvite.Valid -> parsed.invite
        }
        if (isExpired(invite)) return fail(PairingError.EXPIRED)
        candidate = invite
        return PairingState.ConfirmHost(invite.nodeId, invite.expiresAtMs).also { _state.value = it }
    }

    /** "Connect": dial the confirmed host and redeem. No-op unless a host is awaiting confirmation. */
    suspend fun confirm(): PairingState {
        if (_state.value !is PairingState.ConfirmHost) return _state.value
        return verify()
    }

    /** "Retry" after an unreachable host: dial the same, still unused, code again. */
    suspend fun retry(): PairingState {
        val current = _state.value
        if (current !is PairingState.Failed || !current.retryable) return current
        return verify()
    }

    /** "Cancel" on the trust prompt or while verifying: nothing is saved, the code is dropped. */
    fun cancel() {
        attempt++
        candidate = null
        _state.value = PairingState.Idle
    }

    /** Back to idle, e.g. "Scan again". */
    fun reset() = cancel()

    private suspend fun verify(): PairingState {
        val invite = candidate ?: return fail(PairingError.MALFORMED)
        if (isExpired(invite)) return fail(PairingError.EXPIRED)
        val mine = ++attempt
        _state.value = PairingState.Pairing(invite.hostFingerprint)
        val outcome = redeemSafely(invite)
        if (mine != attempt) return _state.value // cancelled meanwhile: never persist a late answer
        return when (outcome) {
            is RedeemOutcome.Failed -> fail(outcome.error, retryable = outcome.error == PairingError.HOST_UNREACHABLE)
            is RedeemOutcome.Paired -> persist(outcome)
        }
    }

    private fun isExpired(invite: ScannedInvite) = nowMs() >= invite.expiresAtMs + CLOCK_SKEW_ALLOWANCE_MS

    private suspend fun redeemSafely(invite: ScannedInvite): RedeemOutcome = try {
        withTimeoutOrNull(verifyTimeoutMs) { redeemer.redeem(invite, deviceName) }
            ?: RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
    } catch (cancelled: CancellationException) {
        _state.value = PairingState.Idle
        throw cancelled
    } catch (_: Exception) {
        RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
    }

    private suspend fun persist(outcome: RedeemOutcome.Paired): PairingState = try {
        store.saveAndActivate(outcome.host)
        candidate = null
        PairingState.Paired(outcome.host, outcome.alreadyPaired, deviceName).also { _state.value = it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(PairingError.SAVE_FAILED)
    }

    private fun fail(error: PairingError, retryable: Boolean = false): PairingState {
        if (!retryable) candidate = null
        return PairingState.Failed(error, retryable).also { _state.value = it }
    }

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

        /** Verifying gives up after this long and reports the host unreachable (dpv7m.5 design: ~15 s). */
        const val VERIFY_TIMEOUT_MS = 15_000L
    }
}
