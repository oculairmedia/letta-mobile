package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.chat.projection.EchoCompactionInstrumentation.resetForTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic regression coverage for the run-block echo-compaction hot path. */
class EchoCompactionLinearCanaryTest {
    @Test
    fun echoCompactionNormalizesEachEligibleMessageOncePerBuild() {
        val corpus = canonicalCorpus()

        resetForTest()
        val items = render(corpus)

        assertTrue(items.isNotEmpty())
        assertEquals(eligibleCount(corpus).toLong(), EchoCompactionInstrumentation.normalizations)
    }

    @Test
    fun normalizationMatchesRetiredRegexSemantics() {
        val samples = listOf(
            "  spaced \t out\n\n text  ",
            "already-normalized",
            "line\r\nfeed",
            "vertical\u000Btab",
            "non breaking\u00A0space and\u2003em space",
            "\t\n",
        )
        val retiredWhitespace = Regex("\\s+")

        samples.forEach { sample ->
            assertEquals(
                sample.trim().replace(retiredWhitespace, " "),
                sample.normalizeRunPanelEchoText(),
                "normalization changed for ${sample.toCharArray().map(Char::code)}",
            )
        }
    }

    @Test
    fun duplicateEchoesPreserveNewestAnchorAndOlderHistory() {
        val echo = "Deploy finished successfully on cluster east with no errors."
        val corpus = listOf(
            assistant("older", echo, runId = null),
            user("prompt"),
            assistant("run-old", echo, runId = "run-1"),
            assistant("run-new", echo, runId = "run-1"),
        )

        val renderedIds = renderedIds(render(corpus))

        assertTrue("older" in renderedIds)
        assertTrue("run-new" in renderedIds, "an all-echo run retains its newest anchor")
        assertTrue("run-old" !in renderedIds)
    }

    @Test
    fun toolCallsRenderInTheFirstBuild() {
        val corpus = canonicalCorpus()
        val firstToolId = corpus.first { !it.toolCalls.isNullOrEmpty() }.id

        assertTrue(firstToolId in renderedIds(render(corpus)))
    }

    @Test
    fun repeatedTailReplacementStaysLinearAndKeepsSettledPrefixStable() {
        val base = canonicalCorpus()
        var expectedPrefix: List<String>? = null

        repeat(REPLACE_TAIL_TICKS) { tick ->
            val streaming = base.dropLast(1) + base.last().copy(content = "streaming tail $tick")
            resetForTest()
            val shapes = render(streaming).map(::renderShape)
            assertTrue(EchoCompactionInstrumentation.normalizations <= streaming.size)
            val prefix = shapes.dropLast(1)
            expectedPrefix?.let { assertEquals(it, prefix, "settled prefix changed at tick $tick") }
            expectedPrefix = prefix
        }
    }

    private fun canonicalCorpus(): List<UiMessage> {
        val random = Random(CORPUS_SIZE)
        val messages = ArrayList<UiMessage>(CORPUS_SIZE)
        var sequence = 0
        while (messages.size < CORPUS_SIZE) {
            val runId = "family-${random.nextInt(4)}"
            messages += user("user-${sequence++}")
            repeat(3 + random.nextInt(3)) { step ->
                val id = "assistant-${sequence++}"
                messages += when (random.nextInt(4)) {
                    0 -> assistant(id, LONG_ECHO, runId)
                    1 -> assistant(id, "reasoning step $step", runId, isReasoning = true)
                    2 -> toolCall(id, runId)
                    else -> assistant(id, "short", runId)
                }
            }
        }
        return messages.take(CORPUS_SIZE)
    }

    private fun render(messages: List<UiMessage>): List<ChatRenderItem> =
        groupMessagesForRender(messages.asReversed().map { it to GroupPosition.None })

    private fun renderedIds(items: List<ChatRenderItem>): Set<String> = buildSet {
        items.forEach { item ->
            when (item) {
                is ChatRenderItem.Single -> add(item.message.id)
                is ChatRenderItem.RunBlock -> item.messages.forEach { add(it.first.id) }
            }
        }
    }

    private fun eligibleCount(messages: List<UiMessage>): Int = messages.count { message ->
        message.role == "assistant" && !message.isReasoning && !message.isError &&
            message.toolCalls.isNullOrEmpty() && message.generatedUi == null &&
            message.approvalRequest == null && message.approvalResponse == null &&
            message.attachments.isEmpty()
    }

    private fun renderShape(item: ChatRenderItem): String = when (item) {
        is ChatRenderItem.Single -> "single:${item.message.id}:${item.key}"
        is ChatRenderItem.RunBlock ->
            "run:${item.runId}:${item.key}:${item.messages.joinToString(",") { it.first.id }}"
    }

    private fun user(id: String) = UiMessage(
        id = id,
        role = "user",
        content = "poke the service",
        timestamp = TIMESTAMP,
    )

    private fun assistant(
        id: String,
        content: String,
        runId: String?,
        isReasoning: Boolean = false,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = TIMESTAMP,
        runId = runId,
        isReasoning = isReasoning,
    )

    private fun toolCall(id: String, runId: String) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = TIMESTAMP,
        runId = runId,
        toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = null)),
    )

    private companion object {
        const val CORPUS_SIZE = 1_079
        const val REPLACE_TAIL_TICKS = 25
        const val TIMESTAMP = "2026-08-23T00:00:00Z"
        const val LONG_ECHO = "Deploy finished successfully on cluster east with no errors reported."
    }
}
