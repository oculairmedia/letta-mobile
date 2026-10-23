package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `TaskOutput` (formerly `BashOutput`): "Watching task bash_3 · running 1m 12s", the task's
 * status as the result reports it (and, while it runs, how long the host has watched), opening to the
 * live tail of its output (the last [TAIL_LINES] lines, following the result as it grows). Its chip
 * opens the task's output where the host can show one.
 */
object TaskOutputToolPresenter : ToolPresenter {
    override val id: String = "task-output"

    override val matcher: ToolMatcher = ToolMatcher(listOf("TaskOutput", "BashOutput", "task_output"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val task = call.taskId() ?: tagged(call.result, "task_id")
        val status = statusOf(call)
        return ToolStepLine(
            verb = ToolVerb.Watching,
            target = listOfNotNull(taskLabel(task), status).joinToString(SEPARATOR),
            previews = listOfNotNull(task?.let(ToolPreviewTarget::TaskOutput)),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        val output = tagged(call.result, "output") ?: tagged(call.result, "stdout") ?: call.resultText() ?: return null
        val lines = output.lines()
        if (lines.size <= TAIL_LINES) return ToolInset.Text(output)
        return ToolInset.Text("… ${lines.size - TAIL_LINES} earlier lines\n" + lines.takeLast(TAIL_LINES).joinToString("\n"))
    }

    /** "running", "completed", "exited 1"… as the result tags it (`<status>`), else its `Status:` line. */
    private fun statusOf(call: ToolCallFacts): String? {
        if (call.failed) return null
        val tagged = tagged(call.result, "status")
        val status = tagged ?: call.result?.lineSequence()?.firstOrNull { it.trim().startsWith("Status:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val exit = tagged(call.result, "exit_code")?.takeIf { it != "0" }
        return when {
            status == null -> null
            exit != null -> "$status, exit $exit"
            status.equals(RUNNING, ignoreCase = true) -> listOfNotNull(RUNNING, call.elapsedMillis?.let(::duration)).joinToString(" ")
            else -> status.lowercase()
        }
    }

    /** "1m 12s", "45s", "1h 3m"; null under a second. */
    private fun duration(millis: Long): String? {
        val seconds = millis / MILLIS_PER_SECOND
        if (seconds < 1) return null
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val rest = seconds % SECONDS_PER_MINUTE
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${rest}s"
            else -> "${rest}s"
        }
    }

    private const val TAIL_LINES = 20
    private const val RUNNING = "running"
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
}
