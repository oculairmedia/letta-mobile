package com.letta.mobile.data.runs

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import kotlin.math.roundToLong

/** The words the Runs page shows for a run and its steps (one place, so every host reads alike). */
object RunFormat {
    /** "850 ms", "4.2 s", "3 m 12 s"; null when the host did not time the run. */
    fun duration(nanos: Long?): String? {
        val ns = nanos?.takeIf { it >= 0 } ?: return null
        val millis = ns / NANOS_PER_MILLI
        return when {
            millis < MILLIS_PER_SECOND -> "$millis ms"
            millis < MILLIS_PER_MINUTE -> tenthsOfSeconds(millis)
            else -> "${millis / MILLIS_PER_MINUTE} m ${(millis % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND} s"
        }
    }

    private fun tenthsOfSeconds(millis: Long): String {
        val tenths = (millis / MILLIS_PER_TENTH.toDouble()).roundToLong()
        return "${tenths / TENTHS_PER_SECOND}.${tenths % TENTHS_PER_SECOND} s"
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
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_TENTH = 100L
    private const val TENTHS_PER_SECOND = 10L
    private const val SHORT_ID = 8
    private const val SEPARATOR = " · "
    private const val UNKNOWN = "unknown"
}
