package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.chat.projection.EchoCompactionInstrumentation.resetForTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-p0gc (Pixel ANR causal slice A) — deterministic canary for the
 * run-block echo compaction hot path.
 *
 * Established evidence: two Pixel ANRs have the main thread in
 * `groupMessagesForRender → compactRunBlockEchoes → runPanelEchoKey`, which
 * used to regex-normalize EVERY older plain assistant message once PER RUN
 * BLOCK — superlinear on long histories (this canary's 1,079-message,
 * 4-run-family corpus drove >100k normalizations; the linear implementation
 * is bounded by one per eligible message).
 *
 * The canary asserts, on a fully deterministic corpus:
 *  1. bounded LINEAR normalization count per build (fresh cache every build),
 *  2. output PARITY with a verbatim copy of the retired regex/set-based
 *     implementation ([legacyGroupMessagesForRender]),
 *  3. tool-call render items appear IMMEDIATELY in the first build,
 *  4. repeated ReplaceTail streaming rebuilds stay bounded and keep the
 *     settled prefix stable.
 */
class EchoCompactionLinearCanaryTest {

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    // Deterministic corpus: 1,079 messages, 4-run-family.
    //
    // Chronological order (oldest first); groupMessagesForRender consumes the
    // reversed list. The family ids cycle so the snapshot holds MANY distinct
    // run blocks re-using only 4 runIds — exactly the shape that made the old
    // algorithm rescan the whole older region once per block.
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    private val longEcho = "Deploy finished successfully on cluster east with no errors reported."
    private val secondEcho = "Retrying the flaky integration suite after the runner restart."
    private var legacyNormalizationCount = 0L

    private fun canonicalCorpus(): List<UiMessage> {
        val rng = Random(seed = 1_079)
        val out = ArrayList<UiMessage>(1_079)
        var seq = 0
        fun nextId(prefix: String): String = "$prefix-${seq++}"
        while (out.size < 1_079) {
            val family = rng.nextInt(4)
            out += UiMessage(
                id = nextId("user"),
                role = "user",
                content = "turn ${out.size}: poke the service",
                timestamp = "2026-08-23T00:00:00Z",
            )
            // Streaming block for this turn: echoes + tool calls + reasoning.
            val blockSize = 3 + rng.nextInt(3)
            repeat(blockSize) { step ->
                when (rng.nextInt(5)) {
                    0 -> out += assistant(
                        id = nextId("a"),
                        content = longEcho,
                        runId = "family-$family",
                    )
                    1 -> out += assistant(
                        id = nextId("a"),
                        content = secondEcho,
                        runId = "family-$family",
                    )
                    2 -> out += assistantToolCall(
                        id = nextId("tc"),
                        name = "Bash",
                        arguments = """{"command":"kubectl get pods -n east"}""",
                        runId = "family-$family",
                    )
                    3 -> out += assistant(
                        id = nextId("r"),
                        content = "reasoning about step $step",
                        runId = "family-$family",
                        isReasoning = true,
                    )
                    else -> out += assistant(
                        id = nextId("s"),
                        content = "ok",
                        runId = "family-$family",
                    )
                }
            }
        }
        return out.take(1_079)
    }

    private fun assistant(
        id: String,
        content: String,
        runId: String?,
        isReasoning: Boolean = false,
    ): UiMessage = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-08-23T01:00:00Z",
        runId = runId,
        isReasoning = isReasoning,
    )

    private fun assistantToolCall(id: String, name: String, arguments: String, runId: String?): UiMessage =
        UiMessage(
            id = id,
            role = "assistant",
            content = "",
            timestamp = "2026-08-23T01:30:00Z",
            runId = runId,
            toolCalls = listOf(UiToolCall(name = name, arguments = arguments, result = null)),
        )

    private fun chronologicalToReversed(messages: List<UiMessage>): List<Pair<UiMessage, GroupPosition>> =
        messages.asReversed().map { it to GroupPosition.None }

    private fun countRenderedMessages(items: List<ChatRenderItem>): Int = items.sumOf { item ->
        when (item) {
            is ChatRenderItem.Single -> 1
            is ChatRenderItem.RunBlock -> item.messages.size
        }
    }

    private fun expectedEligibleNormalizations(messages: List<UiMessage>): Int = messages.count { msg ->
        msg.role == "assistant" && !msg.isReasoning && !msg.isError &&
            msg.toolCalls.isNullOrEmpty() && msg.generatedUi == null &&
            msg.approvalRequest == null && msg.approvalResponse == null &&
            msg.attachments.isEmpty()
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    // 1. Bounded-linear normalizations per build.
    //    RED against the retired algorithm (>100k normalizations here).
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Test
    fun `echo compaction normalizes each message at most once per build`() {
        val corpus = canonicalCorpus()
        assertEquals(1_079, corpus.size)
        val reversedInput = chronologicalToReversed(corpus)

        resetForTest()
        val items = groupMessagesForRender(reversedInput)
        val normalizationsFirstBuild = EchoCompactionInstrumentation.normalizations

        assertTrue(normalizationsFirstBuild > 0, "canary must actually exercise the echo path")
        assertTrue(
            normalizationsFirstBuild <= corpus.size.toLong(),
            "expected ≤1 normalization per message (≤${corpus.size}), got $normalizationsFirstBuild " +
                "— the retired per-block algorithm measures ~10-100x more on this corpus",
        )

        // Fresh cache: an INDEPENDENT second build must be equally bounded.
        resetForTest()
        groupMessagesForRender(reversedInput)
        val normalizationsSecondBuild = EchoCompactionInstrumentation.normalizations
        assertEquals(
            normalizationsFirstBuild,
            normalizationsSecondBuild,
            "per-build normalization count must be identical across fresh-cache builds",
        )
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun `retired compaction exceeds the linear normalization budget`() {
        val corpus = canonicalCorpus()
        legacyNormalizationCount = 0L

        legacyGroupMessagesForRender(chronologicalToReversed(corpus))

        assertTrue(
            legacyNormalizationCount > corpus.size,
            "canary control must prove the retired per-block scan is superlinear: " +
                "normalizations=$legacyNormalizationCount messages=${corpus.size}",
        )
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    // 2. Output parity with the retired regex/set-based implementation.
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Test
    fun `output parity with retired algorithm on the 1079-message canary corpus`() {
        val corpus = canonicalCorpus()
        val reversedInput = chronologicalToReversed(corpus)
        assertEquals(
            legacyGroupMessagesForRender(reversedInput).map(::renderShape),
            groupMessagesForRender(reversedInput).map(::renderShape),
            "linear compaction must preserve exact render output on the canary corpus",
        )
    }

    @Test
    fun `output parity with retired algorithm across edge-case shapes`() {
        val shortRepeat = "ok"
        val edgeCorpus = listOf(
            UiMessage(id = "u0", role = "user", content = "go", timestamp = "t"),
            assistant("a-newest", content = "  spaced \t out\n\n text   beyond twenty four chars ", runId = "fam-a"),
            assistant("a-dup-ws", content = "spaced\tout\n text beyond twenty four chars", runId = "fam-a"),
            assistantToolCall("tc-1", name = "Bash", arguments = "{}", runId = "fam-a"),
            assistant("a-short", content = shortRepeat, runId = "fam-a"),
            assistant("a-reasoning", content = longEcho, runId = "fam-a", isReasoning = true),
            UiMessage(id = "u1", role = "user", content = "again", timestamp = "t"),
            assistant("b-only", content = longEcho, runId = "fam-b"),
            UiMessage(id = "u2", role = "user", content = "more", timestamp = "t"),
            assistant("err-frame", content = longEcho, runId = "fam-c", isReasoning = false)
                .copy(isError = true),
            assistant("c-tail", content = longEcho, runId = "run-prefixed-family-c"),
            // NBSP / unicode whitespace must survive EXACTLY as the regex path did.
            assistant(
                "nbsp-msg",
                content = "non breaking\u00A0space and\u2003em space inside a long echo frame",
                runId = "fam-d",
            ),
            assistant("nbsp-older", content = "non breaking\u00A0space and\u2003em space inside a long echo frame", runId = null),
        ).asReversed().map { it to GroupPosition.None }

        assertEquals(
            legacyGroupMessagesForRender(edgeCorpus).map(::renderShape),
            groupMessagesForRender(edgeCorpus).map(::renderShape),
        )
    }

    /** Structural fingerprint of a render item list (ids + kinds + keys). */
    private fun renderShape(item: ChatRenderItem): String = when (item) {
        is ChatRenderItem.Single -> "Single(${item.message.id},${item.key})"
        is ChatRenderItem.RunBlock ->
            "RunBlock(${item.runId},${item.key},${item.messages.joinToString("|") { it.first.id }})"
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    // 3. Immediate tool render items.
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Test
    fun `tool-call messages render immediately in the first build`() {
        val corpus = canonicalCorpus()
        val items = groupMessagesForRender(chronologicalToReversed(corpus))
        val firstToolCallId = corpus.first { !it.toolCalls.isNullOrEmpty() }.id
        val renderedIds = HashSet<String>()
        items.forEach { item ->
            if (item is ChatRenderItem.Single) renderedIds += item.message.id
            if (item is ChatRenderItem.RunBlock) item.messages.forEach { renderedIds += it.first.id }
        }
        assertTrue(
            firstToolCallId in renderedIds,
            "tool-call message $firstToolCallId must be present in the very first build's render items",
        )
        // Nothing was silently swallowed: dedup only removes echoed prose.
        assertEquals(countRenderedMessages(items), renderedIds.size, "no duplicate render of any message id")
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    // 4. Repeated ReplaceTail / fresh-cache streaming loop.
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Test
    fun `repeated ReplaceTail rebuilds stay bounded and keep settled prefix stable`() {
        val base = canonicalCorpus()
        var tailText = "streaming token"
        repeat(REPLACE_TAIL_TICKS) { tick ->
            tailText += " $tick"
            val streaming = base.dropLast(1) + base.last().copy(content = tailText)
            val input = chronologicalToReversed(streaming)

            resetForTest()
            val items = groupMessagesForRender(input)
            val normalizations = EchoCompactionInstrumentation.normalizations
            assertTrue(
                normalizations <= streaming.size.toLong(),
                "tick $tick: normalizations $normalizations exceeded message count ${streaming.size}",
            )

            // The settled prefix (everything except the streaming tail id) must
            // be identical across ticks — ReplaceTail never rewrites history.
            val prefixShapes = items.map(::renderShape).dropLast(1)
            if (tick == 0) {
                firstTickPrefixShapes = prefixShapes
            } else {
                assertEquals(firstTickPrefixShapes, prefixShapes, "settled prefix changed at tick $tick")
            }
        }
    }

    private var firstTickPrefixShapes: List<String> = emptyList()

    private companion object {
        const val REPLACE_TAIL_TICKS = 25
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    // VERBATIM copy of the RETIRED implementation (pre letta-mobile-p0gc):
    // per-block older-set rebuild + Regex("\\s+") normalization. Kept ONLY
    // as the parity/oracle reference for this canary — production code no
    // longer contains it. Do not optimize this copy; its slowness is the
    // regression being guarded against.
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    private val LegacyWhitespaceRegex = Regex("\\s+")

    private fun legacyGroupMessagesForRender(
        reversed: List<Pair<UiMessage, GroupPosition>>,
    ): List<ChatRenderItem> {
        if (reversed.isEmpty()) return emptyList()

        val runIdCounts = HashMap<String, Int>()
        for ((msg, _) in reversed) {
            val rid = msg.runId.takeIf { msg.role == "assistant" && !it.isNullOrBlank() } ?: continue
            runIdCounts[rid] = (runIdCounts[rid] ?: 0) + 1
        }

        val out = ArrayList<ChatRenderItem>(reversed.size)
        var i = 0
        while (i < reversed.size) {
            val (msg, pos) = reversed[i]
            val runId = msg.runId.takeIf { msg.role == "assistant" && !it.isNullOrBlank() }
            if (runId == null) {
                out.add(ChatRenderItem.Single(msg, pos))
                i++
                continue
            }
            val acc = ArrayList<Pair<UiMessage, GroupPosition>>()
            var j = i
            while (j < reversed.size) {
                val (m, p) = reversed[j]
                if (m.role == "assistant" && m.runId == runId) {
                    acc.add(m to p)
                    j++
                } else {
                    break
                }
            }
            val compactedAcc = legacyCompactRunBlockEchoes(acc, reversed, j)
            if (compactedAcc.size == 1) {
                val isUniqueRun = (runIdCounts[runId] ?: 0) == 1
                out.add(
                    ChatRenderItem.Single(
                        compactedAcc[0].first,
                        compactedAcc[0].second,
                        stableRunKey = if (isUniqueRun) runKey(runId) else null,
                        stableRunId = if (isUniqueRun) runId else null,
                    ),
                )
            } else {
                out.add(
                    ChatRenderItem.RunBlock(
                        runId = runId,
                        messages = compactedAcc.asReversed(),
                        stableKey = legacyRunBlockKey(runId, runIdCounts, compactedAcc),
                    ),
                )
            }
            i = j
        }
        return deduplicateRenderKeys(out)
    }

    private fun legacyCompactRunBlockEchoes(
        accumulator: List<Pair<UiMessage, GroupPosition>>,
        reversed: List<Pair<UiMessage, GroupPosition>>,
        olderStartIndex: Int,
    ): List<Pair<UiMessage, GroupPosition>> {
        if (accumulator.size < 2) return accumulator
        val olderPlainAssistantText = buildSet {
            for (index in olderStartIndex until reversed.size) {
                reversed[index].first.legacyRunPanelEchoKey()?.let(::add)
            }
        }
        val seenInBlock = HashSet<String>()
        return accumulator.filter { (message, _) ->
            val key = message.legacyRunPanelEchoKey() ?: return@filter true
            seenInBlock.add(key) && key !in olderPlainAssistantText
        }.ifEmpty { listOf(accumulator.first()) }
    }

    private fun UiMessage.legacyRunPanelEchoKey(): String? {
        if (role != "assistant") return null
        if (isReasoning || isError) return null
        if (!toolCalls.isNullOrEmpty()) return null
        if (generatedUi != null || approvalRequest != null || approvalResponse != null) return null
        if (attachments.isNotEmpty()) return null
        legacyNormalizationCount++
        val normalized = content.trim().replace(LegacyWhitespaceRegex, " ")
        return normalized.takeIf { it.length >= 24 }
    }

    private fun legacyRunBlockKey(
        runId: String,
        runIdCounts: Map<String, Int>,
        accumulator: List<Pair<UiMessage, GroupPosition>>,
    ): String {
        val matchingMessagesInThisBlock = accumulator.count { (message, _) ->
            message.role == "assistant" && message.runId == runId
        }
        val allMatchingMessagesAreInThisBlock = runIdCounts[runId] == matchingMessagesInThisBlock
        return if (allMatchingMessagesAreInThisBlock) {
            runKey(runId)
        } else {
            "${runKey(runId)}-${accumulator.first().first.id}"
        }
    }
}
