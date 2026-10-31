package com.letta.mobile.data.runs

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.util.time.TimeLabels

/** The words the Runs page shows for a run and its steps (one place, so every host reads alike). */
object RunFormat {
    /** "850ms", "4.2s", "3m 12s" ([TimeLabels.duration] with tenths); null when the host did not time the run. */
    fun duration(nanos: Long?): String? {
        val ns = nanos?.takeIf { it >= 0 } ?: return null
        return TimeLabels.duration(ns / NANOS_PER_MILLI, tenths = true)
    }

    /** The row's second line: status, stop reason and duration, whichever the host reported. */
    fun summaryLine(run: Run): String =
        listOfNotNull(run.status ?: UNKNOWN, run.stopReason?.takeIf { it != run.status }, duration(run.totalDurationNs)).joinToString(SEPARATOR)

    /** The run's id shortened for a row ("…a1b2c3d4"). */
    fun shortId(id: String): String = if (id.length <= SHORT_ID) id else "…" + id.takeLast(SHORT_ID)

    /** One step: its model, tokens and status. */
    fun stepLine(step: Step): String = listOfNotNull(
        step.model,
        step.tokenCount()?.let { "$it tokens" },
        step.status ?: step.stopReason,
    ).joinToString(SEPARATOR).ifEmpty { UNKNOWN }

    private fun Step.tokenCount(): Int? = totalTokens ?: listOfNotNull(promptTokens, completionTokens).takeIf { it.isNotEmpty() }?.sum()

    private const val NANOS_PER_MILLI = 1_000_000L
    private const val SHORT_ID = 8
    private const val SEPARATOR = " · "
    private const val UNKNOWN = "unknown"
}
