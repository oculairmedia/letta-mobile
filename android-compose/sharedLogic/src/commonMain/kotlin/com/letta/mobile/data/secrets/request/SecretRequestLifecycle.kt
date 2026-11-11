package com.letta.mobile.data.secrets.request

import kotlin.time.Instant

/** Something that happens to a request; [SecretRequestLifecycle.apply] decides whether it may. */
sealed interface SecretRequestEvent {
    /** The human submitted a value and the host starts writing it: pending to submitting. */
    data object Claim : SecretRequestEvent

    /** The write was verified: submitting to saved. */
    data object Save : SecretRequestEvent

    /** The write failed for [reason]: submitting to failed. */
    data class Fail(
        /** The fixed failure code. */
        val reason: SecretFailureReason,
    ) : SecretRequestEvent

    /** The human declined: pending to cancelled. */
    data object Cancel : SecretRequestEvent

    /** The request's lifetime ran out: pending to expired. */
    data object Expire : SecretRequestEvent

    /** The host restarted while a write was in progress: submitting to failed (interrupted). */
    data object Interrupt : SecretRequestEvent
}

/** Why [SecretRequestLifecycle.apply] refused an event. */
enum class TransitionRefusal {
    /** The event needs a pending request and this one is not pending. */
    NOT_PENDING,

    /** The event needs a submitting request and this one is not submitting. */
    NOT_SUBMITTING,

    /** The request is pending but its expiry has passed; it must be expired, not answered. */
    OVERDUE,

    /** Expiry was asked for before the request's expiry time. */
    NOT_DUE,
}

/** The outcome of applying an event to a request. */
sealed interface SecretRequestTransition {
    /** The event was allowed; [record] is the request afterwards. */
    data class Applied(
        /** The updated request. */
        val record: SecretRequestRecord,
    ) : SecretRequestTransition

    /** The event was refused for [refusal]; the request is unchanged. */
    data class Refused(
        /** Why it was refused. */
        val refusal: TransitionRefusal,
    ) : SecretRequestTransition
}

/**
 * The request lifecycle as pure functions:
 *
 * ```
 * pending --Claim--> submitting --Save--> saved
 *    |                   |---Fail/Interrupt--> failed
 *    |--Cancel--> cancelled
 *    '--Expire (at or after expiresAt)--> expired
 * ```
 *
 * Terminal states accept no event, so a request is answered at most once. A pending request whose
 * expiry has passed accepts only [SecretRequestEvent.Expire].
 */
object SecretRequestLifecycle {
    /** Applies [event] to [record] at [now]. */
    fun apply(record: SecretRequestRecord, event: SecretRequestEvent, now: Instant): SecretRequestTransition =
        when (event) {
            SecretRequestEvent.Claim -> answer(record, now, SecretRequestStatus(SecretRequestState.SUBMITTING))
            SecretRequestEvent.Cancel -> answer(record, now, settled(SecretRequestState.CANCELLED, now))
            SecretRequestEvent.Expire -> expire(record, now)
            SecretRequestEvent.Save -> finish(record, settled(SecretRequestState.SAVED, now))
            is SecretRequestEvent.Fail -> finish(record, settled(SecretRequestState.FAILED, now, event.reason))
            SecretRequestEvent.Interrupt ->
                finish(record, settled(SecretRequestState.FAILED, now, SecretFailureReason.INTERRUPTED))
        }

    /** Whether [record] is pending and its expiry is at or before [now]. */
    fun isDue(record: SecretRequestRecord, now: Instant): Boolean =
        record.status.state == SecretRequestState.PENDING && now.toEpochMilliseconds() >= record.timing.expiresAtMs

    /** [records] with every due pending request expired at [now]. */
    fun expireDue(records: List<SecretRequestRecord>, now: Instant): List<SecretRequestRecord> =
        records.map { record -> appliedOrSame(record, SecretRequestEvent.Expire, now) }

    /** [records] with every submitting request failed as interrupted: run once when a host starts. */
    fun recoverInterrupted(records: List<SecretRequestRecord>, now: Instant): List<SecretRequestRecord> =
        records.map { record -> appliedOrSame(record, SecretRequestEvent.Interrupt, now) }

    /**
     * [records] without terminal requests settled more than [SecretRequestLimits.SETTLED_RETENTION_MS]
     * before [now]. Pending and submitting requests are never pruned.
     */
    fun prune(records: List<SecretRequestRecord>, now: Instant): List<SecretRequestRecord> {
        val cutoff = now.toEpochMilliseconds() - SecretRequestLimits.SETTLED_RETENTION_MS
        return records.filterNot { record -> (record.status.settledAtMs ?: Long.MAX_VALUE) < cutoff }
    }

    private fun appliedOrSame(record: SecretRequestRecord, event: SecretRequestEvent, now: Instant): SecretRequestRecord =
        (apply(record, event, now) as? SecretRequestTransition.Applied)?.record ?: record

    private fun answer(record: SecretRequestRecord, now: Instant, next: SecretRequestStatus): SecretRequestTransition = when {
        record.status.state != SecretRequestState.PENDING -> SecretRequestTransition.Refused(TransitionRefusal.NOT_PENDING)
        isDue(record, now) -> SecretRequestTransition.Refused(TransitionRefusal.OVERDUE)
        else -> SecretRequestTransition.Applied(record.copy(status = next))
    }

    private fun expire(record: SecretRequestRecord, now: Instant): SecretRequestTransition = when {
        record.status.state != SecretRequestState.PENDING -> SecretRequestTransition.Refused(TransitionRefusal.NOT_PENDING)
        !isDue(record, now) -> SecretRequestTransition.Refused(TransitionRefusal.NOT_DUE)
        else -> SecretRequestTransition.Applied(record.copy(status = settled(SecretRequestState.EXPIRED, now)))
    }

    private fun finish(record: SecretRequestRecord, next: SecretRequestStatus): SecretRequestTransition =
        if (record.status.state == SecretRequestState.SUBMITTING) {
            SecretRequestTransition.Applied(record.copy(status = next))
        } else {
            SecretRequestTransition.Refused(TransitionRefusal.NOT_SUBMITTING)
        }

    private fun settled(
        state: SecretRequestState,
        now: Instant,
        reason: SecretFailureReason? = null,
    ): SecretRequestStatus = SecretRequestStatus(state, reason, now.toEpochMilliseconds())
}
