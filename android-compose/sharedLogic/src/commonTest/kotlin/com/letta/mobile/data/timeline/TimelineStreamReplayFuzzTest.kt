package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.FuzzCaseFailure
import com.letta.mobile.util.FuzzCorpus
import com.letta.mobile.util.checkFuzz
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
                val random = scenarioRandom(seed, scenario)
                val ops = generateOperations(seed, scenario, random)
                try {
                    assertScenario(seed, scenario, ops)
                } catch (failure: FuzzCaseFailure) {
                    val shrunk = minimizeBySuffixDeletion(
                        operations = ops,
                        isValid = { minimizedOps -> minimizedOps.all { it.isStructurallyValid() } },
                        invokeScenario = { minimizedOps -> assertScenario(seed, scenario, minimizedOps) },
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

    private fun assertScenario(seed: Long, scenarioIndex: Int, ops: List<StreamReplayOp>) {
        val conversationId = "conv-fuzz-$seed-$scenarioIndex"
        var timeline = Timeline(conversationId = conversationId)
        var pending: PersistentMap<String, ToolReturnMessage> = persistentMapOf()
        val oracle = StreamOracle()
        ops.forEachIndexed { opIndex, op ->
            val stepped = applyStreamOp(timeline, pending, oracle, op)
            timeline = stepped.timeline
            pending = stepped.pending
            assertSnapshotMatchesOracle(seed, scenarioIndex, opIndex, timeline, oracle)
        }
        val runB = "run-$seed-$scenarioIndex-b"
        if (ops.any { it is StreamReplayOp.AssistantCumulative && it.message.runId == runB }) {
            val runIds = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
                .mapNotNull { it.runId }
                .distinct()
            checkFuzz(seed, scenarioIndex, condition = runIds.contains(runB)) {
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
        seed: Long,
        scenarioIndex: Int,
        opIndex: Int,
        timeline: Timeline,
        oracle: StreamOracle,
    ) {
        val production = timeline.confirmedSnapshot()
        val expected = oracle.snapshot()
        checkFuzz(seed, scenarioIndex, opIndex, condition = production.keys == expected.keys) {
            "serverId set mismatch prod=${production.keys} oracle=${expected.keys}"
        }
        expected.forEach { (serverId, body) ->
            checkFuzz(seed, scenarioIndex, opIndex, condition = production[serverId] == body) {
                "content mismatch for $serverId prod=${production[serverId]} oracle=$body"
            }
        }
        checkFuzz(
            seed,
            scenarioIndex,
            opIndex,
            condition = timeline.events.filterIsInstance<TimelineEvent.Confirmed>().size == production.size,
        ) {
            "confirmed row count drift"
        }
    }

    private fun generateOperations(seed: Long, scenarioIndex: Int, random: Random): List<StreamReplayOp> {
        val opCount = random.nextInt(3, 18)
        val runA = "run-$seed-$scenarioIndex-a"
        val runB = "run-$seed-$scenarioIndex-b"
        val ops = mutableListOf<StreamReplayOp>()
        var assistantSeq = 0
        var streamIdCounter = 0
        repeat(opCount) {
            when (random.nextInt(5)) {
                0, 1 -> {
                    val id = "user-$seed-$scenarioIndex-${ops.size}"
                    ops += StreamReplayOp.User(
                        UserMessage(
                            id = id,
                            contentRaw = JsonPrimitive(random.pickText()),
                            runId = if (random.nextBoolean()) runA else runB,
                        ),
                    )
                }
                else -> {
                    val generated = generateAssistantStream(
                        seed,
                        scenarioIndex,
                        random,
                        runA,
                        runB,
                        streamIdCounter + 1,
                        assistantSeq,
                    )
                    streamIdCounter = generated.nextStreamId
                    assistantSeq = generated.nextSeq
                    ops += generated.ops
                }
            }
        }
        return ops
    }

    private data class AssistantGen(val ops: List<StreamReplayOp>, val nextStreamId: Int, val nextSeq: Int)

    private fun generateAssistantStream(
        seed: Long,
        scenarioIndex: Int,
        random: Random,
        runA: String,
        runB: String,
        streamIdCounter: Int,
        startSeq: Int,
    ): AssistantGen {
        val serverId = "asst-$seed-$scenarioIndex-$streamIdCounter"
        val otid = "otid-$seed-$scenarioIndex-$streamIdCounter"
        val assistantRun = if (random.nextBoolean()) runA else runB
        val chunks = random.nextInt(1, 6)
        var text = ""
        var assistantSeq = startSeq
        var lastMsg: AssistantMessage? = null
        val out = mutableListOf<StreamReplayOp>()
        repeat(chunks) { chunkIndex ->
            text += random.pickToken()
            assistantSeq++
            val msg = AssistantMessage(
                id = serverId,
                contentRaw = JsonPrimitive(text),
                otid = otid,
                runId = assistantRun,
                seqId = assistantSeq,
                textSeq = assistantSeq,
            )
            lastMsg = msg
            out += StreamReplayOp.AssistantCumulative(msg)
            if (chunkIndex > 0 && random.nextInt(4) == 0) {
                val replaySeq = assistantSeq - random.nextInt(1, assistantSeq.coerceAtLeast(1))
                val replayText = text.dropLast(random.nextInt(0, text.length.coerceAtLeast(1)))
                out += StreamReplayOp.AssistantCumulative(
                    msg.copy(
                        seqId = replaySeq,
                        textSeq = replaySeq,
                        contentRaw = JsonPrimitive(replayText.ifEmpty { text.take(1) }),
                    ),
                )
            }
        }
        if (scenarioIndex % 9 == 0 && chunks >= 2 && lastMsg != null) {
            val latePrefix = text.dropLast(1)
            out += StreamReplayOp.AssistantCumulative(
                lastMsg!!.copy(
                    seqId = assistantSeq + 1,
                    textSeq = assistantSeq + 1,
                    contentRaw = JsonPrimitive(latePrefix),
                ),
            )
            assistantSeq++
        }
        return AssistantGen(out, streamIdCounter, assistantSeq)
    }

    private fun Random.pickText(): String = pickToken() + " " + pickToken()
    private fun Random.pickToken(): String {
        val len = 3 + nextInt(5)
        return buildString(len) {
            repeat(len) { append(('a'..'z').random(this@pickToken)) }
        }
    }

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
            val serverId = message.otid?.let { otid ->
                otidToServerId[otid] ?: message.id.also { otidToServerId[otid] = it }
            } ?: message.id
            val existing = assistants[serverId]
            if (existing == null) {
                assistants[serverId] = AssistantState(message.otid, message.runId, seq, message.content)
                return
            }
            if (seq <= existing.maxSeq) {
                return
            }
            val incoming = message.content
            if (incoming.length < existing.text.length && existing.text.startsWith(incoming)) {
                return
            }
            assistants[serverId] = existing.copy(
                maxSeq = seq,
                text = incoming,
                runId = message.runId ?: existing.runId,
            )
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
