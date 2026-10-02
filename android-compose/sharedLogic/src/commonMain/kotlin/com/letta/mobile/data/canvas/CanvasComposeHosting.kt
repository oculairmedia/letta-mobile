package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.CanvasComposeGuide
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeErrorCode
import com.letta.mobile.data.canvas.compose.ComposeOutcome
import com.letta.mobile.data.canvas.compose.ComposeProblem
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the two hosts of `canvas_compose` share around [CanvasComposeService] (letta-mobile-bglj6.12):
 * the Iroh host ([HostCanvasTools], through the relay's log) and an app talking to its own App
 * Server ([CanvasComposeTool], through the canvas store and the live session). Each host resolves
 * the canvas and checks the caller its own way, then hands the call here, so the request is read,
 * answered and recorded the same on both.
 *
 * - A receipt is [ExternalToolResult.Success] and a refusal [ExternalToolResult.Error], each with
 *   [ComposeOutcome.json] as its body: the TOOL_CALL event keeps it under the call id and the chat
 *   reads the receipt card from it (CanvasArtifactReceipts).
 * - A canvas the caller may not use, or that is not there, is a refusal of the same shape
 *   ([ComposeErrorCode.UNAUTHORIZED], [ComposeErrorCode.CANVAS_NOT_FOUND]), not free text.
 */
internal object CanvasComposeHosting {
    /** [Telemetry] attribute naming which host answered. */
    const val IROH_HOST = "iroh-host"
    const val APP = "app"

    /**
     * The compose call [input] on the canvas [canvasId] (scene [sceneJson] at [sceneRevision]),
     * published through [publish], answered as a tool result.
     */
    suspend fun compose(
        host: String,
        input: JsonObject,
        canvasId: String,
        sceneJson: String,
        sceneRevision: Long,
        toolCallId: String?,
        publish: suspend (List<CanvasOp>) -> Long,
    ): ExternalToolResult {
        val request = tolerant(input)
        if (toolCallId.isNullOrBlank() && request["artifact_id"] == null) {
            // The artifact id is derived from the tool call id so a retried call lands on the same
            // artifact; without either, a retry would put a second copy on the board.
            Telemetry.event(
                CanvasComposeService.TAG, "compose.noToolCallId",
                "host" to host, "canvasId" to canvasId,
                level = Telemetry.Level.WARN,
            )
        }
        val outcome = CanvasComposeService.compose(
            input = request,
            canvasId = canvasId,
            sceneJson = sceneJson,
            sceneRevision = sceneRevision,
            toolCallId = toolCallId?.takeIf { it.isNotBlank() },
            publish = publish,
        )
        record(host, canvasId, toolCallId, outcome)
        return answer(outcome)
    }

    /** A refusal before anything was compiled: the canvas could not be used. */
    fun refused(host: String, code: ComposeErrorCode, message: String, toolCallId: String?): ExternalToolResult {
        val outcome = ComposeOutcome.Refused(CanvasComposeContract.refusal(code, listOf(ComposeProblem("", code.name, message))))
        record(host, canvasId = null, toolCallId = toolCallId, outcome = outcome)
        return answer(outcome)
    }

    /** The code of an access refusal: the hosts word an ACL refusal "Unauthorized: ...". */
    fun deniedCode(reason: String): ComposeErrorCode =
        if (reason.startsWith(UNAUTHORIZED_PREFIX)) ComposeErrorCode.UNAUTHORIZED else ComposeErrorCode.CANVAS_NOT_FOUND

    /** `canvas_compose_guide`: the whole format. */
    fun guide(): ExternalToolResult = ExternalToolResult.Success(CanvasComposeGuide.text)

    private fun answer(outcome: ComposeOutcome): ExternalToolResult = when (outcome) {
        is ComposeOutcome.Done -> ExternalToolResult.Success(outcome.json)
        is ComposeOutcome.Refused -> ExternalToolResult.Error(outcome.json)
    }

    /**
     * `dry_run` as the other canvas tools take it ([CanvasDryRun.requested]): a model that writes
     * "true" for true is not refused for it. The schema is otherwise held strictly.
     */
    private fun tolerant(input: JsonObject): JsonObject {
        val dryRun = input[CanvasDryRun.PARAM] as? JsonPrimitive ?: return input
        if (!dryRun.isString) return input
        val flag = dryRun.content.trim().lowercase().toBooleanStrictOrNull() ?: return input
        return JsonObject(input + (CanvasDryRun.PARAM to JsonPrimitive(flag)))
    }

    /** One event per answered call, as the board's other writes are recorded. */
    private fun record(host: String, canvasId: String?, toolCallId: String?, outcome: ComposeOutcome) {
        when (outcome) {
            is ComposeOutcome.Done -> Telemetry.event(
                CanvasComposeService.TAG, "compose.answered",
                "host" to host,
                "canvasId" to canvasId,
                "artifactId" to outcome.receipt.artifactId,
                "status" to outcome.receipt.status.name,
                "revision" to outcome.receipt.revision,
                "items" to outcome.receipt.items.size,
                "retry" to outcome.receipt.warnings.isNotEmpty(),
                "toolCallId" to toolCallId,
            )
            is ComposeOutcome.Refused -> Telemetry.event(
                CanvasComposeService.TAG, "compose.refused",
                "host" to host,
                "canvasId" to canvasId,
                "code" to outcome.refusal.code.name,
                "problems" to outcome.refusal.problems.size,
                "toolCallId" to toolCallId,
                level = Telemetry.Level.WARN,
            )
        }
    }

    private const val UNAUTHORIZED_PREFIX = "Unauthorized"
}
