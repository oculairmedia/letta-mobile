package com.letta.mobile.architecture

import com.letta.mobile.architecture.CountRatchetBaseline.Shrink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/** Proves the typed-id ratchet counters and baseline drift on synthetic input (letta-mobile-by4vm.23). */
class IdRatchetFixtureTest {
    @Test
    fun `counts each id-prefix literal and skips comment lines`() {
        val text = """
            val a = "conv-default-" + agentId
            val b = listOf("client-", "cm-")
            val c = id.removePrefix("agent-")
            val d = "client-${'$'}{x}"
            // "cm-" in a line comment
             * "agent-" in KDoc
            val e = "my-client-x"
            const val TAG = "agent-starter"
        """.trimIndent()

        assertEquals(5, IdRatchetScan.idLiteralCount(text))
    }

    @Test
    fun `counts default only when the line is about an id or conversation`() {
        val text = """
            if (conversationId == "default") return
            put("id", "default")
            const val DEFAULT_CONVERSATION = "default"
            val key = flowOf("default")
            type = "default"
            val android = "default"
        """.trimIndent()

        assertEquals(3, IdRatchetScan.idLiteralCount(text))
    }

    @Test
    fun `counts raw String id parameters but not properties or other types`() {
        val text = """
            fun load(agentId: String, conversationId: String?, runId: RunId) {}
            class Row(val messageId: String, requestID: String, private var toolCallId: String?)
            val handler: (approvalId: String) -> Unit = {}
            fun other(agentIds: List<String>, idle: String, builderId: StringBuilder) {}
            // fun ignored(agentId: String)
        """.trimIndent()

        assertEquals(4, IdRatchetScan.rawIdParameterCount(text))
    }

    @Test
    fun `growth is NEW and an unbaselined file is NEW`() {
        val drift = CountRatchetBaseline.drift(
            actual = mapOf("a.kt" to 3, "b.kt" to 1),
            baseline = listOf("a.kt" to 2),
            baselineName = "x.txt",
            shrink = Shrink.FREE,
        )

        assertEquals(
            listOf(
                "NEW: a.kt has 3, x.txt allows 2; fix it rather than raising the baseline",
                "NEW: b.kt has 1, x.txt allows 0; fix it rather than raising the baseline",
            ),
            drift,
        )
    }

    @Test
    fun `shrinking is free unless the baseline must be updated`() {
        val actual = mapOf("a.kt" to 1)
        val baseline = listOf("a.kt" to 2, "gone.kt" to 4)

        assertEquals(emptyList<String>(), CountRatchetBaseline.drift(actual, baseline, "x.txt", Shrink.FREE))
        assertEquals(
            listOf(
                "STALE: a.kt has 1, lower its x.txt line from 2 (delete it at 0)",
                "STALE: gone.kt has 0, lower its x.txt line from 4 (delete it at 0)",
            ),
            CountRatchetBaseline.drift(actual, baseline, "x.txt", Shrink.MUST_UPDATE),
        )
    }

    @Test
    fun `unsorted or duplicated baselines are rejected`() {
        val unsorted = CountRatchetBaseline.drift(emptyMap(), listOf("b.kt" to 0, "a.kt" to 0), "x.txt", Shrink.FREE)
        val duplicated = CountRatchetBaseline.drift(emptyMap(), listOf("a.kt" to 0, "a.kt" to 0), "x.txt", Shrink.FREE)

        assertEquals(listOf("x.txt must be sorted by path with one unique entry per line"), unsorted)
        assertEquals(listOf("x.txt must be sorted by path with one unique entry per line"), duplicated)
    }

    @Test
    fun `baseline files round trip and skip comments`(@TempDir dir: Path) {
        val file = dir.resolve("b.txt")
        file.writeText("# header\n\n" + CountRatchetBaseline.render(mapOf("z.kt" to 2, "a.kt" to 5)))

        assertEquals(listOf("a.kt" to 5, "z.kt" to 2), CountRatchetBaseline.read(file))
    }
}
