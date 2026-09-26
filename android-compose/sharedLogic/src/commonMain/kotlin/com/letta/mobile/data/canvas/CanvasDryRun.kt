package com.letta.mobile.data.canvas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One problem a dry run found: the op ([opIndex], `2.0` inside a batch op), the rule it breaks and why. */
@Serializable
data class CanvasDryRunProblem(
    @SerialName("op_index")
    val opIndex: String,
    val op: String,
    val invariant: String,
    val subject: String,
    val detail: String,
)

/**
 * What `canvas.apply_ops` / `canvas.replace_scene` with `dry_run: true` answer (letta-mobile-qygvv.30):
 * whether the batch would be published, checked against the canvas at [revision], and nothing
 * published either way.
 */
@Serializable
data class CanvasDryRunResult(
    @SerialName("dry_run")
    val dryRun: Boolean = true,
    val valid: Boolean,
    val revision: Long,
    @SerialName("canvas_id")
    val canvasId: String? = null,
    val problems: List<CanvasDryRunProblem> = emptyList(),
    val message: String? = null,
)

object CanvasDryRun {
    const val PARAM = "dry_run"

    /** Whether the call asked only to check: `dry_run` true, as a boolean or the string a model may send. */
    fun requested(input: JsonObject): Boolean {
        val value = input[PARAM] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: value.content.equals("true", ignoreCase = true)
    }

    fun result(check: CanvasBatchCheck, revision: Long, canvasId: String?): CanvasDryRunResult = when (check) {
        is CanvasBatchCheck.Valid -> CanvasDryRunResult(valid = true, revision = revision, canvasId = canvasId)
        is CanvasBatchCheck.Invalid -> CanvasDryRunResult(
            valid = false,
            revision = revision,
            canvasId = canvasId,
            problems = check.violations.map(::problem),
            message = check.message,
        )
    }

    private fun problem(violation: CanvasBatchViolation) = CanvasDryRunProblem(
        opIndex = violation.opIndex,
        op = violation.op,
        invariant = violation.violation.invariant.wire,
        subject = violation.violation.subject,
        detail = violation.violation.detail,
    )
}
