package com.letta.mobile.data.model

import com.letta.mobile.runtime.RuntimeUserInputTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A plan an approval asks the person to accept: its markdown [content], and the plan file it is
 * written to ([filePath]) when the call names one.
 */
data class PlanPreview(val content: String, val filePath: String?) {
    /** [filePath] shortened to its last two segments (`.../plans/name.md`), as the reference shows it. */
    val shortFilePath: String?
        get() = filePath?.let { path ->
            val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (segments.size <= 2) path else ".../" + segments.takeLast(2).joinToString("/")
        }
}

/**
 * letta-mobile-8fq4j: the plan a parked approval proposes, and the answer that accepts it. One
 * reading for every host (desktop, Android, web) and both transports.
 *
 * Two calls carry a plan, mirroring the official Letta desktop app (0.34.10):
 * - `ExitPlanMode` (`{ plan: "<markdown>" }`), a runtime user-input tool
 *   ([RuntimeUserInputTools]): it always waits for the person, under every permission mode.
 *   Approving closes it with `Allow(updated_input = <its own arguments>)` so the runtime receives
 *   the plan it approved; rejecting denies it with the person's feedback, and the agent stays in
 *   plan mode to revise.
 * - A file write or patch that lands in a `plans/<name>.md` file (the reference's
 *   `PlanWritePreview`, "Ready to code? Here is your plan:"): an ordinary approval, decided like
 *   any other write, whose card also shows the plan it writes.
 */
object PlanApproval {
    const val EXIT_PLAN_MODE_TOOL: String = RuntimeUserInputTools.EXIT_PLAN_MODE

    /** The reference's `isWriteTool` names. */
    private val WRITE_TOOLS = setOf("write", "Write", "WriteFile", "write_file", "write_file_gemini", "WriteFileGemini")

    /** The reference's `isPatchTool` names. */
    private val PATCH_TOOLS = setOf("apply_patch", "ApplyPatch", "memory_apply_patch")

    /** The reference's `isPlanFilePath`, on `/`-separated paths (a Windows host's `\` reads as `/`). */
    private val PLAN_FILE_PATH = Regex("/plans/[^/]+\\.md$")

    private const val ADD_FILE = "*** Add File:"
    private const val UPDATE_FILE = "*** Update File:"

    fun isExitPlanMode(toolName: String?): Boolean = toolName == EXIT_PLAN_MODE_TOOL

    /** The plan [toolName] proposes with [argumentsJson], or null when the call proposes none. */
    fun preview(toolName: String, argumentsJson: String?): PlanPreview? {
        if (argumentsJson.isNullOrBlank()) return null
        return when (toolName) {
            EXIT_PLAN_MODE_TOOL -> exitPlanMode(argumentsJson)
            in WRITE_TOOLS -> argumentsJson.toArgsObject()?.planWrite()
            in PATCH_TOOLS -> argumentsJson.toArgsObject()?.string("input")?.let(::planPatch)
            else -> null
        }
    }

    /**
     * The approval reason that accepts an ExitPlanMode call with [argumentsJson]: its own
     * arguments as `updated_input`, over the structured-answer channel
     * ([AskUserQuestion.encodeAnswerReason]). Null when the arguments are not an object, so the
     * answer falls back to a plain allow that keeps the call's original input.
     */
    fun approveReason(argumentsJson: String?): String? =
        argumentsJson?.toArgsObject()?.let(AskUserQuestion::encodeAnswerReason)

    /** The `plan` field; a call without one shows its raw arguments, never nothing. */
    private fun exitPlanMode(argumentsJson: String): PlanPreview {
        val plan = argumentsJson.toArgsObject()?.string("plan")?.takeIf { it.isNotBlank() }
        return PlanPreview(content = plan ?: "```json\n$argumentsJson\n```", filePath = null)
    }

    private fun JsonObject.planWrite(): PlanPreview? {
        val content = string("content")?.takeIf { it.isNotEmpty() } ?: return null
        val path = string("file_path")?.takeIf(::isPlanFilePath) ?: return null
        return PlanPreview(content, path)
    }

    /** The reference's `tryParsePlanPatch`: the added and context lines of a patch whose last file is a plan file. */
    private fun planPatch(patch: String): PlanPreview? {
        var file: String? = null
        val lines = mutableListOf<String>()
        for (line in patch.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith(ADD_FILE) -> file = trimmed.removePrefix(ADD_FILE).trim()
                trimmed.startsWith(UPDATE_FILE) -> file = trimmed.removePrefix(UPDATE_FILE).trim()
                trimmed.startsWith("*** ") || trimmed.startsWith("@@") -> Unit
                file != null && (line.startsWith("+") || line.startsWith(" ")) -> lines += line.substring(1)
            }
        }
        val path = file?.takeIf(::isPlanFilePath) ?: return null
        return if (lines.isEmpty()) null else PlanPreview(lines.joinToString("\n"), path)
    }

    private fun isPlanFilePath(path: String): Boolean = PLAN_FILE_PATH.containsMatchIn(path.replace('\\', '/'))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
