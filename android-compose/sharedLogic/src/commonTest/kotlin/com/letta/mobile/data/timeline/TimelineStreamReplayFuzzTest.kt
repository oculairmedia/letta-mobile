package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.FuzzCaseFailure
import com.letta.mobile.util.FuzzCorpus
import com.letta.mobile.util.FuzzScenarioContext
import com.letta.mobile.util.minimizeBySuffixDeletion
import com.letta.mobile.util.scenarioRandom
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import com.letta.mobile.util.Telemetry

/**
 * Seeded replay tests for [reduceStreamFrame]: valid cumulative assistant streams, user rows,
 * duplicate-prefix replays, stale seq drops, and distinct run ids.
 *
 * Oracle: latest forward cumulative snapshot per server id (stale seq ignored); does not call
 * [mergeStreamText] or re-invoke the reducer as its own expected value.
 *
 * Excluded: tool-call graphs, approval settlement, reconcile mutations, skill envelopes,
 * terminal-state resurrection (not owned by this text-only reducer path), and invalid
 * cross-conversation frames (rejected by construction).
 */
class TimelineStreamReplayFuzzTest {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
        Telemetry.chatHotPathDebugEnabled.set(false)
    }

    @Test
    fun seededStreamReplayMatchesIndependentOracle() {
        var totalCases = 0
        FuzzCorpus.timelineSeeds.forEach { seed ->
            repeat(FuzzCorpus.SCENARIOS_PER_SEED) { scenario ->
                totalCases++
                val replayScenario = StreamReplayScenario(seed, scenario, scenarioRandom(seed, scenario))
                val ops = replayScenario.generateOperations()
                try {
                    assertScenario(replayScenario, ops)
                } catch (failure: FuzzCaseFailure) {
                    val shrunk = minimizeBySuffixDeletion(
                        operations = ops,
                        isValid = { minimizedOps -> minimizedOps.all { it.isStructurallyValid() } },
                        invokeScenario = { minimizedOps ->
                            assertScenario(replayScenario, minimizedOps)
                        },
                        formatOp = { it.label() },
                    )
                    throw FuzzCaseFailure(
                        seed = failure.seed,
                        scenarioIndex = failure.scenarioIndex,
                        operationIndex = failure.operationIndex,
                        detail = "${failure.message} minimized=${shrunk.minimized.size}/${ops.size} trace=${shrunk.trace}",
                    ).also { it.initCause(failure) }
                }
            }
        }
        check(totalCases == FuzzCorpus.timelineSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED)
    }

    private fun assertScenario(scenario: StreamReplayScenario, ops: List<StreamReplayOp>) {
        val ctx = scenario.ctx
        var timeline = Timeline(conversationId = scenario.conversationId)
        var pending: PersistentMap<String, ToolReturnMessage> = persistentMapOf()
        val oracle = StreamOracle()
        ops.forEachIndexed { opIndex, op ->
            val stepped = applyStreamOp(timeline, pending, oracle, op)
            timeline = stepped.timeline
            pending = stepped.pending
            assertSnapshotMatchesOracle(ctx, opIndex, timeline, oracle)
        }
        val runB = scenario.runB
        if (ops.any { it is StreamReplayOp.AssistantCumulative && it.message.runId == runB }) {
            val runIds = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
                .mapNotNull { it.runId }
                .distinct()
            ctx.check(condition = runIds.contains(runB)) {
                "expected runB in confirmed events, got $runIds"
            }
        }
    }

    private data class StreamStep(
        val timeline: Timeline,
        val pending: PersistentMap<String, ToolReturnMessage>,
    )

    private fun applyStreamOp(
        timeline: Timeline,
        pending: PersistentMap<String, ToolReturnMessage>,
        oracle: StreamOracle,
        op: StreamReplayOp,
    ): StreamStep {
        return when (op) {
            is StreamReplayOp.User -> {
                oracle.ingestUser(op.message)
                val out = reduceStreamFrame(
                    TimelineReducerInput(
                        prev = timeline,
                        frame = op.message,
                        pendingToolReturnsByCallId = pending,
                        agentId = "agent-fuzz",
                    ),
                )
                StreamStep(out.next, out.updatedPendingToolReturnsByCallId)
            }
            is StreamReplayOp.AssistantCumulative -> {
                oracle.ingestAssistantCumulative(op.message)
                val out = reduceStreamFrame(
                    TimelineReducerInput(
                        prev = timeline,
                        frame = op.message,
                        pendingToolReturnsByCallId = pending,
                        agentId = "agent-fuzz",
                    ),
                )
                StreamStep(out.next, out.updatedPendingToolReturnsByCallId)
            }
        }
    }

    private fun assertSnapshotMatchesOracle(
        ctx: FuzzScenarioContext,
        opIndex: Int,
        timeline: Timeline,
        oracle: StreamOracle,
    ) {
        val production = timeline.confirmedSnapshot()
        val expected = oracle.snapshot()
        ctx.checkAt(opIndex, condition = production.keys == expected.keys) {
            "serverId set mismatch prod=${production.keys} oracle=${expected.keys}"
        }
        expected.forEach { (serverId, body) ->
            ctx.checkAt(opIndex, condition = production[serverId] == body) {
                "content mismatch for $serverId prod=${production[serverId]} oracle=$body"
            }
        }
        ctx.checkAt(
            opIndex,
            condition = timeline.events.filterIsInstance<TimelineEvent.Confirmed>().size == production.size,
        ) {
            "confirmed row count drift"
        }
    }

    private class StreamReplayScenario(
        val seed: Long,
        val scenarioIndex: Int,
        val random: Random,
    ) {
        val ctx = FuzzScenarioContext(seed, scenarioIndex)
        val conversationId = "conv-fuzz-$seed-$scenarioIndex"
        val runA = "run-$seed-$scenarioIndex-a"
        val runB = "run-$seed-$scenarioIndex-b"
        private var nextStreamId = 0
        private var assistantSeq = 0

        fun generateOperations(): List<StreamReplayOp> {
            val opCount = random.nextInt(3, 18)
            val ops = mutableListOf<StreamReplayOp>()
            repeat(opCount) {
                when (random.nextInt(5)) {
                    0, 1 -> ops += nextUserOp(ops.size)
                    else -> ops += nextAssistantStreamOps()
                }
            }
            return ops
        }

        private fun nextUserOp(opIndex: Int): StreamReplayOp.User {
            val id = "user-$seed-$scenarioIndex-$opIndex"
            return StreamReplayOp.User(
                UserMessage(
                    id = id,
                    contentRaw = JsonPrimitive(random.fuzzPickText()),
                    runId = if (random.nextBoolean()) runA else runB,
                ),
            )
        }

        private fun nextAssistantStreamOps(): List<StreamReplayOp> {
            nextStreamId++
            val generator = AssistantCumulativeGenerator(
                scenario = this,
                serverId = "asst-$seed-$scenarioIndex-$nextStreamId",
                otid = "otid-$seed-$scenarioIndex-$nextStreamId",
                assistantRun = if (random.nextBoolean()) runA else runB,
            )
            val generated = generator.generate()
            assistantSeq = generated.nextSeq
            return generated.ops
        }

        private class AssistantCumulativeGenerator(
            private val scenario: StreamReplayScenario,
            private val serverId: String,
            private val otid: String,
            private val assistantRun: String,
        ) {
            private val random = scenario.random

            fun generate(): AssistantGenResult {
                val chunks = random.nextInt(1, 6)
                var text = ""
                var seq = scenario.assistantSeq
                var lastMsg: AssistantMessage? = null
                val out = mutableListOf<StreamReplayOp>()
                repeat(chunks) { chunkIndex ->
                    text += random.fuzzPickToken()
                    seq++
                    val msg = AssistantMessage(
                        id = serverId,
                        contentRaw = JsonPrimitive(text),
                        otid = otid,
                        runId = assistantRun,
                        seqId = seq,
                        textSeq = seq,
                    )
                    lastMsg = msg
                    out += StreamReplayOp.AssistantCumulative(msg)
                    appendStaleReplayIfNeeded(out, msg, text, seq, chunkIndex)
                }
                val finalSeq = appendLatePrefixIfScheduled(out, lastMsg, text, seq, chunks)
                return AssistantGenResult(out, finalSeq)
            }

            private fun appendStaleReplayIfNeeded(
                out: MutableList<StreamReplayOp>,
                msg: AssistantMessage,
                text: String,
                seq: Int,
                chunkIndex: Int,
            ) {
                if (chunkIndex == 0 || random.nextInt(4) != 0) return
                val replaySeq = seq - random.nextInt(1, seq.coerceAtLeast(1))
                val replayText = text.dropLast(random.nextInt(0, text.length.coerceAtLeast(1)))
                out += StreamReplayOp.AssistantCumulative(
                    msg.copy(
                        seqId = replaySeq,
                        textSeq = replaySeq,
                        contentRaw = JsonPrimitive(replayText.ifEmpty { text.take(1) }),
                    ),
                )
            }

            private fun appendLatePrefixIfScheduled(
                out: MutableList<StreamReplayOp>,
                lastMsg: AssistantMessage?,
                text: String,
                seq: Int,
                chunks: Int,
            ): Int {
                if (scenario.scenarioIndex % 9 != 0 || chunks < 2 || lastMsg == null) return seq
                val latePrefix = text.dropLast(1)
                out += StreamReplayOp.AssistantCumulative(
                    lastMsg.copy(
                        seqId = seq + 1,
                        textSeq = seq + 1,
                        contentRaw = JsonPrimitive(latePrefix),
                    ),
                )
                return seq + 1
            }
        }
    }

    private data class AssistantGenResult(val ops: List<StreamReplayOp>, val nextSeq: Int)

    private sealed class StreamReplayOp {
        abstract fun isStructurallyValid(): Boolean
        abstract fun label(): String
        data class User(val message: UserMessage) : StreamReplayOp() {
            override fun isStructurallyValid(): Boolean = message.id.isNotBlank()
            override fun label(): String = "user:${message.id}"
        }
        data class AssistantCumulative(val message: AssistantMessage) : StreamReplayOp() {
            override fun isStructurallyValid(): Boolean =
                message.id.isNotBlank() && message.textSeq != null && message.seqId != null
            override fun label(): String = "asst:${message.id}:s${message.seqId}"
        }
    }

    /**
     * Independent expected state: users append once; assistants keep the latest strictly-forward
     * cumulative snapshot per server id (known-growing generator text, stale seq ignored).
     */
    private class StreamOracle {
        private val users = linkedMapOf<String, String>()
        private val assistants = linkedMapOf<String, AssistantState>()
        private val otidToServerId = mutableMapOf<String, String>()

        fun ingestUser(message: UserMessage) {
            if (message.id !in users) {
                users[message.id] = message.content
            }
        }

        fun ingestAssistantCumulative(message: AssistantMessage) {
            val seq = message.seqId ?: return
            val serverId = resolveServerId(message)
            val existing = assistants[serverId]
            if (existing == null) {
                assistants[serverId] = AssistantState(message.otid, message.runId, seq, message.content)
                return
            }
            if (!shouldAdvanceAssistant(existing, message, seq)) return
            assistants[serverId] = existing.copy(
                maxSeq = seq,
                text = message.content,
                runId = message.runId ?: existing.runId,
            )
        }

        private fun resolveServerId(message: AssistantMessage): String {
            val otid = message.otid
            if (otid != null) {
                return otidToServerId.getOrPut(otid) { message.id }
            }
            return message.id
        }

        private fun shouldAdvanceAssistant(existing: AssistantState, message: AssistantMessage, seq: Int): Boolean {
            if (seq <= existing.maxSeq) return false
            val incoming = message.content
            if (incoming.length < existing.text.length && existing.text.startsWith(incoming)) {
                return false
            }
            return true
        }

        fun snapshot(): Map<String, String> {
            val out = linkedMapOf<String, String>()
            users.forEach { (id, text) -> out[id] = text }
            assistants.forEach { (id, state) -> out[id] = state.text }
            return out
        }

        private data class AssistantState(
            val otid: String?,
            val runId: String?,
            val maxSeq: Int,
            val text: String,
        )
    }

    private fun Timeline.confirmedSnapshot(): Map<String, String> =
        events.filterIsInstance<TimelineEvent.Confirmed>().associate { it.serverId to it.content }
}

private fun Random.fuzzPickText(): String = fuzzPickToken() + " " + fuzzPickToken()

private fun Random.fuzzPickToken(): String {
    val len = 3 + nextInt(5)
    return buildString(len) {
        repeat(len) { append(('a'..'z').random(this@fuzzPickToken)) }
    }
}
