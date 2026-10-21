package com.letta.mobile.data.model

/**
 * A background shell's `<task-notification>` report, split into the parts letta-code wrote
 * (each shown in its own code block). A `Bash` report is `$ <command>`, an optional detail line (`Exit code: 1`),
 * then `[stdout]` / `[stderr]` tails (`[stdout - last 20 of 54 lines]`), or `(no output)`. An
 * `exec_command` report has `Session ID: <id>` after the command and one unlabelled output tail.
 * Any part may be missing, and middle truncation may cut through one; the text is kept verbatim.
 */
data class BackgroundCommandReport(
    val command: String?,
    val detail: String?,
    val streams: List<CommandStream>,
)

/** One output tail. [label] is the header as written (`stdout - last 20 of 54 lines`), or null. */
data class CommandStream(
    val kind: Kind,
    val label: String?,
    val text: String,
) {
    /** Which stream a tail came from; [Output] is exec_command's unlabelled mix. */
    enum class Kind { Stdout, Stderr, Output }
}

/** The background tasks whose reports are shell output: `Bash` (`bash_N`) and `exec_command`. */
fun UiSubagentNotification.isCommand(): Boolean =
    COMMAND_TASK_PREFIXES.any { taskId?.startsWith(it) == true }

private val COMMAND_TASK_PREFIXES = listOf("bash_", "exec_")

/** Splits a command [result] into its sections; never fails, unparsed text stays in the command. */
fun parseBackgroundCommandReport(result: String): BackgroundCommandReport {
    val headers = STREAM_HEADER.findAll(result).toList()
    val prelude = (headers.firstOrNull()?.let { result.substring(0, it.range.first) } ?: result).trimEnd()
    val tailed = headers.mapIndexed { index, header ->
        val end = headers.getOrNull(index + 1)?.range?.first ?: result.length
        CommandStream(
            kind = if (header.groupValues[1] == "stderr") CommandStream.Kind.Stderr else CommandStream.Kind.Stdout,
            label = header.value.removePrefix("[").removeSuffix("]"),
            text = result.substring(header.range.last + 1, end).trim('\n', '\r').trimEnd(),
        )
    }
    val head = prelude.removeSuffix(NO_OUTPUT).trimEnd()
    val session = SESSION_LINE.find(head)
    if (session != null) {
        val output = head.substring(session.range.last + 1).trim('\n', '\r')
        val stream = output.takeIf { it.isNotBlank() && it != NO_NEW_OUTPUT }
            ?.let { CommandStream(CommandStream.Kind.Output, null, it) }
        return BackgroundCommandReport(commandOf(head.substring(0, session.range.first)), null, listOfNotNull(stream) + tailed)
    }
    val detail = DETAIL_LINE.findAll(head).lastOrNull()
    return BackgroundCommandReport(
        command = commandOf(detail?.let { head.substring(0, it.range.first) } ?: head),
        detail = detail?.groupValues?.get(1)?.trim(),
        streams = tailed,
    )
}

private fun commandOf(text: String): String? =
    text.trim('\n', '\r').removePrefix("$ ").trimEnd().takeIf { it.isNotBlank() }

private const val NO_OUTPUT = "(no output)"
private const val NO_NEW_OUTPUT = "(no new output)"

private val STREAM_HEADER = Regex("""(?m)^\[(stdout|stderr)(?: - last \d+ of \d+ lines)?]$""")
private val SESSION_LINE = Regex("""(?m)^Session ID: \S+$""")

/** letta-code's completion details, each its own paragraph after the command. */
private val DETAIL_LINE = Regex(
    listOf(
        """Exit code: -?\d+""",
        "Terminated by signal before exiting",
        "Output file write failed.*",
        "Error: .*",
        """Command timed out after \d+ms""",
    ).joinToString("|", prefix = "(?m)\n\n(", postfix = ")$"),
)
