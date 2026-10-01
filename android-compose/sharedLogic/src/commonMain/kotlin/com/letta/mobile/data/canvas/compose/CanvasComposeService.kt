package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasBatchCheck
import com.letta.mobile.data.canvas.CanvasBatchValidator
import com.letta.mobile.data.canvas.CanvasBatchViolation
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/** How a compose call ended: a receipt (published, a dry run, or a retry of a published one), or a refusal. */
sealed interface ComposeOutcome {
    /** The tool's answer body: the receipt, or the refusal for `ExternalToolResult.Error`. */
    val json: String

    data class Done(val receipt: ComposeReceipt) : ComposeOutcome {
        override val json: String get() = CanvasComposeContract.json.encodeToString(ComposeReceipt.serializer(), receipt)
    }

    data class Refused(val refusal: ComposeRefusal) : ComposeOutcome {
        override val json: String get() = CanvasComposeContract.json.encodeToString(ComposeRefusal.serializer(), refusal)
    }
}

/**
 * What a publisher throws to say why the board would not take the batch in the contract's own
 * terms ([ComposeErrorCode.UNAUTHORIZED], [ComposeErrorCode.CANVAS_NOT_FOUND], ...). Any other
 * failure is answered as [ComposeErrorCode.BOARD_REFUSED].
 */
class ComposePublishException(val code: ComposeErrorCode, message: String) : Exception(message)

/**
 * The one entry point both hosts call for `canvas_compose` (letta-mobile-bglj6.10, plan section
 * 3.3): compile ([CanvasComposeCompiler]), hold the batch to the board's rules
 * ([CanvasBatchValidator], the only validator, which runs CanvasSceneValidator over every op),
 * then publish through the host's existing path and answer with the receipt.
 *
 * All or nothing: a refused request publishes nothing, and a batch the board refuses is answered
 * as BOARD_REFUSED with each violation at the pointer of the item whose op broke the rule and the
 * board's own rule name as its code. A dry run stops before publishing and answers the same
 * receipt with status `dry_run`. A retry of an artifact already on the board with the same content
 * publishes nothing and answers its receipt again.
 */
object CanvasComposeService {
    const val TAG = "CanvasCompose"

    /** The problem code of a publish that failed for a reason the publisher did not name. */
    const val PUBLISH_FAILED = "publish.failed"

    const val ALREADY_PUBLISHED_WARNING = "This artifact was already on the board with this content; nothing was published again."

    /**
     * [input] (the tool's arguments) composed onto the canvas [canvasId], whose scene is [sceneJson]
     * at [sceneRevision]. [toolCallId] names the artifact when the request does not
     * ([CanvasComposeIds.derived]). [check] is the batch validator against [sceneJson] unless the
     * host checks against its own copy; [publish] writes the checked ops through the host's op log
     * (stamping their identity) and returns the revision they landed at.
     */
    suspend fun compose(
        input: JsonElement,
        canvasId: String,
        sceneJson: String,
        sceneRevision: Long? = null,
        toolCallId: String? = null,
        check: (List<CanvasOp>) -> CanvasBatchCheck = { CanvasBatchValidator.check(sceneJson, it) },
        publish: suspend (List<CanvasOp>) -> Long,
    ): ComposeOutcome = compose(input, canvasId, sceneJson, sceneRevision, toolCallId, check, publish) { request, scene, fallback ->
        CanvasComposeCompiler.compile(request, scene, fallback)
    }

    /** [compose] with the compile step replaceable, so a test can corrupt what the compiler emits. */
    internal suspend fun compose(
        input: JsonElement,
        canvasId: String,
        sceneJson: String,
        sceneRevision: Long?,
        toolCallId: String?,
        check: (List<CanvasOp>) -> CanvasBatchCheck,
        publish: suspend (List<CanvasOp>) -> Long,
        compile: (JsonElement, String, () -> String) -> ComposeCompilation,
    ): ComposeOutcome {
        val ready = when (val compiled = compile(input, sceneJson) { CanvasComposeIds.derived(toolCallId) }) {
            is ComposeCompilation.Refused -> return ComposeOutcome.Refused(compiled.refusal)
            is ComposeCompilation.Ready -> compiled
        }
        if (ready.alreadyPublished) {
            return ComposeOutcome.Done(ready.receipt(canvasId, ComposeStatus.PUBLISHED, sceneRevision, listOf(ALREADY_PUBLISHED_WARNING)))
        }
        val valid = when (val checked = check(ready.ops)) {
            is CanvasBatchCheck.Invalid -> return ComposeOutcome.Refused(boardRefusal(checked.violations, ready.itemPaths))
            is CanvasBatchCheck.Valid -> checked
        }
        if (ready.dryRun) return ComposeOutcome.Done(ready.receipt(canvasId, ComposeStatus.DRY_RUN, revision = null))
        val revision = try {
            publish(valid.ops)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ComposePublishException) {
            return ComposeOutcome.Refused(publishRefusal(e.code, e.message, ready, canvasId))
        } catch (e: Exception) {
            return ComposeOutcome.Refused(publishRefusal(ComposeErrorCode.BOARD_REFUSED, e.message ?: e::class.simpleName, ready, canvasId))
        }
        return ComposeOutcome.Done(ready.receipt(canvasId, ComposeStatus.PUBLISHED, revision))
    }

    /**
     * The board's violations as problems: each at the item whose op it was blamed on (`2` or `2.0`
     * inside a batch op; compose emits none), the board's rule as the code, its text as the message.
     */
    fun boardRefusal(violations: List<CanvasBatchViolation>, itemPaths: List<String>): ComposeRefusal =
        CanvasComposeContract.refusal(
            ComposeErrorCode.BOARD_REFUSED,
            violations.map { violation ->
                val index = violation.opIndex.substringBefore('.').toIntOrNull()
                ComposeProblem(
                    path = index?.let { itemPaths.getOrNull(it) }.orEmpty(),
                    code = violation.violation.invariant.wire,
                    message = violation.toString(),
                )
            },
        )

    private fun publishRefusal(code: ComposeErrorCode, message: String?, ready: ComposeCompilation.Ready, canvasId: String): ComposeRefusal {
        // Loud: the batch passed every rule and the board still did not take it.
        Telemetry.event(
            TAG, "publish.failed",
            "canvasId" to canvasId, "artifactId" to ready.artifactId, "ops" to ready.ops.size, "code" to code.name, "reason" to message,
            level = Telemetry.Level.WARN,
        )
        val problemCode = if (code == ComposeErrorCode.BOARD_REFUSED) PUBLISH_FAILED else code.name
        return CanvasComposeContract.refusal(
            code,
            listOf(ComposeProblem("", problemCode, "the board did not take the artifact: ${message ?: "no reason given"}")),
        )
    }
}
