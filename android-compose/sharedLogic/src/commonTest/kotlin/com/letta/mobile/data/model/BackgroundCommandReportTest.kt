package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-2a7yv: a background shell's report, split as letta-code wrote it. */
class BackgroundCommandReportTest {
    private val heredoc = "python3 - \"\$J\" <<'PY'\nimport json, sys\n\n" +
        "for line in open(sys.argv[1]):\n    print(json.loads(line)['id'])\nPY"

    @Test
    fun aBashReportKeepsItsMultiLineCommandDetailAndBothTails() {
        val result = "$ $heredoc\n\nExit code: 0\n\n[stdout - last 2 of 40 lines]\n{\"id\":1}\n{\"id\":2}\n\n" +
            "[stderr]\nTraceback (most recent call last):\n  File \"<stdin>\", line 3\nKeyError: 'id'"

        val report = parseBackgroundCommandReport(result)

        assertEquals(heredoc, report.command)
        assertEquals("Exit code: 0", report.detail)
        assertEquals(
            listOf(
                CommandStream(CommandStream.Kind.Stdout, "stdout - last 2 of 40 lines", "{\"id\":1}\n{\"id\":2}"),
                CommandStream(
                    CommandStream.Kind.Stderr, "stderr",
                    "Traceback (most recent call last):\n  File \"<stdin>\", line 3\nKeyError: 'id'",
                ),
            ),
            report.streams,
        )
    }

    @Test
    fun aSilentCommandHasNoStreams() {
        val report = parseBackgroundCommandReport("$ sleep 5\n\nExit code: 0\n\n(no output)")

        assertEquals("sleep 5", report.command)
        assertEquals("Exit code: 0", report.detail)
        assertEquals(emptyList(), report.streams)
    }

    @Test
    fun anExecReportCarriesItsSessionOutputAsOneStream() {
        val report = parseBackgroundCommandReport("$ npm test\n\nSession ID: 7\n\nok 1\nok 2")

        assertEquals("npm test", report.command)
        assertNull(report.detail)
        assertEquals(listOf(CommandStream(CommandStream.Kind.Output, null, "ok 1\nok 2")), report.streams)
    }

    @Test
    fun aBashTaskIsACommand() {
        fun notification(task: String?) = UiSubagentNotification(
            status = "completed", summary = null, result = null, usage = null, transcriptUri = null, taskId = task,
        )
        assertEquals(listOf(true, true, false), listOf("bash_54", "exec_3", "task_1").map { notification(it).isCommand() })
    }
}
