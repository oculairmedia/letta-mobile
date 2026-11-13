package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.journal.SecretJournalProblem
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr

/**
 * The service's only telemetry. Every attribute is a fixed code or a bounded label of a
 * well-formed request id; item names, field names, purposes, values and exception text never
 * appear.
 */
internal object SecretRequestTelemetry {
    private const val TAG = "SecretRequest"
    private const val MALFORMED_ID = "malformed"

    fun created(requestId: SecretRequestId) {
        Telemetry.event(TAG, "request.created", "request" attr label(requestId))
    }

    fun requestRefused(problem: SecretRequestProblem) {
        Telemetry.event(TAG, "request.refused", "problem" attr problem.name.lowercase(), level = Telemetry.Level.WARN)
    }

    fun answerRefused(requestId: SecretRequestId, error: SecretServiceError) {
        Telemetry.event(
            TAG,
            "answer.refused",
            "request" attr label(requestId),
            "error" attr error.name.lowercase(),
            level = Telemetry.Level.WARN,
        )
    }

    fun settled(requestId: SecretRequestId, receipt: SecretRequestReceipt) {
        Telemetry.event(
            TAG,
            "request.settled",
            "request" attr label(requestId),
            "status" attr receipt.status.name.lowercase(),
            "reason" attr receipt.reason?.name?.lowercase(),
        )
    }

    fun keystoreOverran(requestId: SecretRequestId) {
        Telemetry.event(TAG, "keystore.overran", "request" attr label(requestId), level = Telemetry.Level.WARN)
    }

    fun journalUnavailable(problem: SecretJournalProblem) {
        Telemetry.event(TAG, "journal.unavailable", "problem" attr problem.name.lowercase(), level = Telemetry.Level.ERROR)
    }

    private fun label(requestId: SecretRequestId): String = if (requestId.isWellFormed) requestId.logLabel else MALFORMED_ID
}
