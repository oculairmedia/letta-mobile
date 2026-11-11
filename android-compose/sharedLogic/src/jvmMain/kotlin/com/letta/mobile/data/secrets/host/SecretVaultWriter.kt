package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretRequestEvent
import com.letta.mobile.data.secrets.request.SecretRequestTarget
import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreErrorCode
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.KeystoreProviderId
import com.letta.mobile.plugin.keystore.KeystoreResult
import com.letta.mobile.plugin.keystore.PutOptions
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretPath
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretScope
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.WriteMode
import com.letta.mobile.plugin.keystore.valueOrNull
import kotlinx.coroutines.CancellationException

/**
 * Writes one submitted value to its fixed target so that either the verified value is stored or
 * nothing new is left behind:
 *
 * - An existing slot is replaced; its previous value is held (host memory only) to restore it if
 *   the read-back does not match, then wiped.
 * - A missing slot is created only when the provider can list and the listing proves the whole
 *   item is absent, so undoing the write is deleting the item. A missing slot in an existing (or
 *   unprovable) item is refused with [SecretFailureReason.TARGET_NOT_WRITABLE]: keystore v1 has no
 *   field delete, so a failed verification there could not be undone.
 * - After every write the slot is read back and compared in constant time; a mismatch undoes the
 *   write ([SecretFailureReason.VERIFY_FAILED], or [SecretFailureReason.ROLLBACK_FAILED] when the
 *   undo fails too).
 *
 * Every provider error maps to a fixed reason; diagnostics and exception messages are dropped.
 * A provider that throws is treated as [KeystoreError.Internal]. The caller owns and wipes the value.
 */
internal class SecretVaultWriter(private val provider: KeystoreProvider) {
    /** Writes [value] to [target]; answers the event that settles the request. */
    suspend fun write(target: SecretRequestTarget, value: SecretValue): SecretRequestEvent {
        val ref = refOf(target)
        if (ref.problemFor(provider.describe().id) != null) return fail(SecretFailureReason.TARGET_NOT_WRITABLE)
        return when (val prior = guarded { provider.get(ref) }) {
            is KeystoreResult.Success -> try {
                writeVerified(ref, value, WriteMode.REPLACE) { provider.put(ref, prior.value, PutOptions(WriteMode.REPLACE)) }
            } finally {
                prior.value.wipe()
            }
            is KeystoreResult.Failure -> if (prior.error is KeystoreError.NotFound) create(ref, value) else fail(reasonFor(prior.error))
        }
    }

    private suspend fun create(ref: SecretRef, value: SecretValue): SecretRequestEvent {
        val problem = itemAbsenceProblem(ref)
        if (problem != null) return fail(problem)
        return writeVerified(ref, value, WriteMode.CREATE) { provider.delete(ref.item) }
    }

    private suspend fun itemAbsenceProblem(ref: SecretRef): SecretFailureReason? {
        if (KeystoreCapability.LIST !in provider.describe().capabilities) return SecretFailureReason.TARGET_NOT_WRITABLE
        return when (val listing = guarded { provider.list(SecretScope(ref.provider, ref.path)) }) {
            is KeystoreResult.Failure -> reasonFor(listing.error)
            is KeystoreResult.Success -> {
                val present = ref.item in listing.value.entries || listing.value.truncated
                if (present) SecretFailureReason.TARGET_NOT_WRITABLE else null
            }
        }
    }

    private suspend fun writeVerified(
        ref: SecretRef,
        value: SecretValue,
        mode: WriteMode,
        undo: suspend () -> KeystoreResult<Unit>,
    ): SecretRequestEvent {
        val written = guarded { provider.put(ref, value, PutOptions(mode)) }
        if (written is KeystoreResult.Failure) return fail(reasonFor(written.error))
        if (readBackMatches(ref, value)) return SecretRequestEvent.Save
        val undone = guarded(undo)
        return fail(if (undone is KeystoreResult.Success) SecretFailureReason.VERIFY_FAILED else SecretFailureReason.ROLLBACK_FAILED)
    }

    private suspend fun readBackMatches(ref: SecretRef, value: SecretValue): Boolean {
        val readBack = guarded { provider.get(ref) }.valueOrNull() ?: return false
        return try {
            readBack.contentEqualsConstantTime(value)
        } finally {
            readBack.wipe()
        }
    }

    private suspend fun <T> guarded(call: suspend () -> KeystoreResult<T>): KeystoreResult<T> = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expectedProviderFailure: Exception) {
        KeystoreResult.Failure(KeystoreError.Internal())
    }

    private fun refOf(target: SecretRequestTarget): SecretRef =
        SecretRef(KeystoreProviderId(target.keystore.value), SecretPath(target.item.value), SecretField(target.field.value))

    private fun fail(reason: SecretFailureReason): SecretRequestEvent = SecretRequestEvent.Fail(reason)

    private fun reasonFor(error: KeystoreError): SecretFailureReason = REASONS[error.code] ?: SecretFailureReason.VAULT_ERROR

    private companion object {
        val REASONS = mapOf(
            KeystoreErrorCode.LOCKED to SecretFailureReason.VAULT_LOCKED,
            KeystoreErrorCode.UNAVAILABLE to SecretFailureReason.VAULT_UNAVAILABLE,
            KeystoreErrorCode.DENIED to SecretFailureReason.VAULT_DENIED,
            KeystoreErrorCode.UNSUPPORTED to SecretFailureReason.VAULT_UNSUPPORTED,
            KeystoreErrorCode.CONFLICT to SecretFailureReason.TARGET_NOT_WRITABLE,
            KeystoreErrorCode.INVALID_VALUE to SecretFailureReason.VALUE_REJECTED,
        )
    }
}
