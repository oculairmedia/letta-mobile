package com.letta.mobile.ui.chat.surface.timeline.rows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runtime prepends a `key=value` metadata line to a subagent's report; only a leading block made
 * entirely of known keys is taken off (and read), anything else leaves the report as it is.
 */
class SubagentReportParsingTest {
    @Test
    fun aKnownMetadataLineIsReadAndTakenOffTheReport() {
        val (meta, body) = splitReportHeader(
            "subagent_type=codex subagent_id=sub-1 subagent_status=success agent_id=codex_1 runtime_session_id=s-1\n\nDone. **Pick: B.**",
        )
        assertEquals(
            mapOf(
                "subagent_type" to "codex",
                "subagent_id" to "sub-1",
                "subagent_status" to "success",
                "agent_id" to "codex_1",
                "runtime_session_id" to "s-1",
            ),
            meta,
        )
        assertEquals("Done. **Pick: B.**", body)
    }

    @Test
    fun aBlockSpreadOverLinesIsReadWhole() {
        val (meta, body) = splitReportHeader("subagent_type=codex subagent_id=sub-1\nagent_id=codex_1\nThe report.")
        assertEquals(setOf("subagent_type", "subagent_id", "agent_id"), meta.keys)
        assertEquals("The report.", body)
    }

    @Test
    fun someKnownKeysAloneAreEnough() {
        val (meta, body) = splitReportHeader("subagent_status=failed\nIt could not start.")
        assertEquals(mapOf("subagent_status" to "failed"), meta)
        assertEquals("It could not start.", body)
    }

    @Test
    fun aLineWithAnUnknownKeyIsNotMetadata() {
        val report = "subagent_type=codex colour=blue\nThe report."
        val (meta, body) = splitReportHeader(report)
        assertTrue(meta.isEmpty())
        assertEquals(report, body)
    }

    @Test
    fun aReportWithoutTheBlockIsUntouched() {
        val report = "All four clips rendered.\n\nsubagent_id=sub-1 appears later and stays."
        val (meta, body) = splitReportHeader(report)
        assertTrue(meta.isEmpty())
        assertEquals(report, body)
    }

    @Test
    fun proseThatMerelyContainsEqualsIsNotStripped() {
        val report = "Set retries=3 and timeout=30s in the config, then rerun."
        val (meta, body) = splitReportHeader(report)
        assertTrue(meta.isEmpty())
        assertEquals(report, body)
    }

    @Test
    fun anEmptyValueIsNotMetadata() {
        val report = "subagent_id= \nThe report."
        assertTrue(splitReportHeader(report).first.isEmpty())
    }

    @Test
    fun usageLinesReadAsNumbersByKey() {
        assertEquals(
            mapOf("tool_uses" to 9L, "duration_ms" to 61_000L, "total_tokens" to 12_400L),
            reportUsage("tool_uses: 9\nduration_ms: 61000\ntotal_tokens: 12400\nmodel: gpt"),
        )
        assertTrue(reportUsage(null).isEmpty())
    }
}
