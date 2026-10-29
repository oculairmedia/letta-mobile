package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore.Delivery

/** Attempt-aware pending lifecycle, with compatibility delegation for writers without durable state. */
interface TimelinePendingSendTarget {
    /** Reserve durable pending capacity before invoking transport acceptance; false keeps the send queued. */
    suspend fun acceptPendingSend(
        attempt: TimelinePendingSendAttempt,
        content: String,
        attachments: List<MessageContentPart.Image>,
        accept: () -> Boolean,
    ): Boolean = accept()

    /** Reject queued retries whose echo or dismissal already removed the pending attempt. */
    suspend fun isPendingSendCurrent(attempt: TimelinePendingSendAttempt): Boolean = true

    /** Retain dispatch ownership independently of viewport and runtime replacement. */
    suspend fun ownPendingSend(attempt: TimelinePendingSendAttempt, active: () -> Boolean) {}

    /** Apply an outcome to the identified retry generation. */
    suspend fun markPendingSend(attempt: TimelinePendingSendAttempt, delivery: Delivery)
}
