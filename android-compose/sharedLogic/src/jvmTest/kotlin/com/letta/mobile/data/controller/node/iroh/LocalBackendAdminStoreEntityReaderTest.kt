package com.letta.mobile.data.controller.node.iroh

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * lgns8.9: per-entity coverage for the local-backend readers that took over the
 * former `admin_rest_service` READS (runs, steps, blocks, agent context).
 *
 * Every fixture is synthesised by [LocalBackendFixtureStore] — no real user data
 * is ever copied into the repo. The assertions pin the behaviours that were
 * ported verbatim from admin-shim, because those are what a client decodes:
 * the archive walk rule, cursor semantics, the synthesised block id, and the
 * context token arithmetic.
 */
class LocalBackendAdminStoreEntityReaderTest {

    private fun store(): Pair<LocalBackendAdminStore, File> {
        val root = createTempDirectory("lgns8-9-store").toFile()
        LocalBackendFixtureStore.create(root)
        return LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1") to root
    }

    // ── runs ────────────────────────────────────────────────────────────────

    @Test
    fun runListServesTheLiveRootAndNeverDescendsTheArchive() {
        val (store, _) = store()
        val runs = assertNotNull(store.listRunsProjected(RunQuery()))
        assertEquals(
            listOf(LocalBackendFixtureStore.RUN_ID),
            runs.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            "the live walk must skip runs/_archive (admin-shim lcp-98cm)",
        )
    }

    @Test
    fun runListIncludesTheArchiveOnlyWhenAsked() {
        val (store, _) = store()
        val runs = assertNotNull(store.listRunsProjected(RunQuery(includeArchived = true)))
        assertEquals(
            setOf(LocalBackendFixtureStore.RUN_ID, LocalBackendFixtureStore.ARCHIVED_RUN_ID),
            runs.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet(),
        )
        // Default order is created_at DESC: the newer live run sorts first.
        assertEquals(
            LocalBackendFixtureStore.RUN_ID,
            runs.first().jsonObject.getValue("id").jsonPrimitive.content,
        )
    }

    @Test
    fun runListFiltersByAgentConversationAndStatus() {
        val (store, root) = store()
        LocalBackendFixtureStore.writeRun(root, "run-other", archived = false, agentId = "agent-2")

        val mine = assertNotNull(store.listRunsProjected(RunQuery(agentId = LocalBackendFixtureStore.AGENT_ID)))
        assertEquals(listOf(LocalBackendFixtureStore.RUN_ID), mine.map { it.jsonObject.getValue("id").jsonPrimitive.content })

        assertEquals(2, assertNotNull(store.listRunsProjected(RunQuery(conversationId = "conv-1"))).size)
        assertEquals(0, assertNotNull(store.listRunsProjected(RunQuery(conversationId = "conv-nope"))).size)
        assertEquals(0, assertNotNull(store.listRunsProjected(RunQuery(active = true))).size)
        assertEquals(2, assertNotNull(store.listRunsProjected(RunQuery(statuses = listOf("completed")))).size)
        assertEquals(0, assertNotNull(store.listRunsProjected(RunQuery(stopReason = "max_steps"))).size)
    }

    @Test
    fun runListAppliesTheLimitAndTheAfterCursor() {
        val (store, root) = store()
        LocalBackendFixtureStore.writeRun(root, "run-other", archived = false, agentId = "agent-2")

        assertEquals(1, assertNotNull(store.listRunsProjected(RunQuery(limit = 1))).size)
        val all = assertNotNull(store.listRunsProjected(RunQuery()))
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val after = assertNotNull(store.listRunsProjected(RunQuery(after = all.first())))
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(all.drop(1), after, "`after` must drop everything up to AND including the cursor row")
    }

    @Test
    fun runGetResolvesLiveAndArchivedRunsAndNullsUnknownOnes() {
        val (store, _) = store()
        assertEquals(
            LocalBackendFixtureStore.RUN_ID,
            assertNotNull(store.getRunProjected(LocalBackendFixtureStore.RUN_ID)).getValue("id").jsonPrimitive.content,
        )
        assertNotNull(
            store.getRunProjected(LocalBackendFixtureStore.ARCHIVED_RUN_ID),
            "an archived run must still resolve by id — no run history is lost",
        )
        assertNull(store.getRunProjected("run-unknown"))
    }

    @Test
    fun runJsonIsEmittedVerbatimSoTheWireContractCannotDrift() {
        val (store, _) = store()
        val run = assertNotNull(store.getRunProjected(LocalBackendFixtureStore.RUN_ID))
        assertEquals("completed", run.getValue("status").jsonPrimitive.content)
        assertEquals("conv-1", run.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(LocalBackendFixtureStore.AGENT_ID, run.getValue("agent_id").jsonPrimitive.content)
        assertEquals(listOf("m-1"), run.getValue("message_ids").jsonArray.map { it.jsonPrimitive.content })
    }

    // ── steps ───────────────────────────────────────────────────────────────

    @Test
    fun stepListReadsStepsJsonlForLiveAndArchivedRuns() {
        val (store, _) = store()
        val steps = assertNotNull(store.listStepsProjected(LocalBackendFixtureStore.RUN_ID, StepQuery()))
        assertEquals(
            listOf(LocalBackendFixtureStore.STEP_ID),
            steps.map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            1,
            assertNotNull(store.listStepsProjected(LocalBackendFixtureStore.ARCHIVED_RUN_ID, StepQuery())).size,
        )
    }

    @Test
    fun stepListIsEmptyForARunWithoutSteps() {
        val (store, root) = store()
        File(root, "runs/${LocalBackendFixtureStore.RUN_ID}/steps.jsonl").delete()
        assertEquals(0, assertNotNull(store.listStepsProjected(LocalBackendFixtureStore.RUN_ID, StepQuery())).size)
    }

    @Test
    fun runExistsDistinguishesAnUnknownRunFromARunWithNoSteps() {
        val (store, _) = store()
        assertTrue(store.runExists(LocalBackendFixtureStore.RUN_ID))
        assertTrue(store.runExists(LocalBackendFixtureStore.ARCHIVED_RUN_ID))
        assertTrue(!store.runExists("run-unknown"))
    }

    // ── blocks ──────────────────────────────────────────────────────────────

    @Test
    fun blockListUnionsEveryAgentsSystemMemoryFiles() {
        val (store, root) = store()
        LocalBackendFixtureStore.writeAgent(root, "agent-2", name = "Second")
        LocalBackendFixtureStore.writeBlock(root, "agent-2", "human", "someone")

        val blocks = assertNotNull(store.listBlocksProjected(null, null)).map { it.jsonObject }
        assertEquals(
            setOf(LocalBackendFixtureStore.BLOCK_LABEL, "human"),
            blocks.map { it.getValue("label").jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun blockIdIsTheLockedSha256OfAgentAndLabel() {
        val (store, _) = store()
        val block = assertNotNull(store.listBlocksProjected(null, null)).first().jsonObject
        assertEquals(LocalBackendFixtureStore.blockId, block.getValue("id").jsonPrimitive.content)
        assertTrue(
            block.getValue("id").jsonPrimitive.content.startsWith("block-"),
            "the id prefix is a locked wire invariant",
        )
    }

    @Test
    fun blockGetFindsABlockByItsSynthesisedIdAndNullsUnknownOnes() {
        val (store, _) = store()
        val block = assertNotNull(store.getBlockProjected(LocalBackendFixtureStore.blockId))
        assertEquals(LocalBackendFixtureStore.BLOCK_VALUE, block.getValue("value").jsonPrimitive.content)
        assertEquals(LocalBackendBlockReader.BLOCK_VALUE_LIMIT, block.getValue("limit").jsonPrimitive.content.toInt())
        assertNull(store.getBlockProjected("block-nope"))
    }

    @Test
    fun blockProjectionMatchesTheAgentListProjectionForTheSameFile() {
        val (store, _) = store()
        val fromBlockList = assertNotNull(store.listBlocksProjected(null, null)).first().jsonObject
        val fromAgentList = assertNotNull(store.listAgentsProjected(null, null))
            .first().jsonObject.getValue("blocks").jsonArray.first().jsonObject
        assertEquals(
            fromAgentList,
            fromBlockList,
            "agent.list blocks and block.list must be the same projection — one drifting breaks the Block Library",
        )
    }

    // ── agent context ───────────────────────────────────────────────────────

    @Test
    fun agentContextReadsTheSystemPromptSidecarAndTheTranscript() {
        val (store, _) = store()
        val context = assertNotNull(store.agentContextProjected(LocalBackendFixtureStore.AGENT_ID, null))
        assertEquals(LocalBackendFixtureStore.SYSTEM_PROMPT, context.getValue("system_prompt").jsonPrimitive.content)
        assertEquals(1, context.getValue("num_messages").jsonPrimitive.content.toInt())
        // letta-mobile-cyh28: the transcript is no longer fanned out into the answer.
        assertEquals(0, context.getValue("messages").jsonArray.size)
    }

    @Test
    fun agentContextEstimatesSectionsLikeLettaCodeAndSaysItIsPartial() {
        val (store, _) = store()
        val context = assertNotNull(store.agentContextProjected(LocalBackendFixtureStore.AGENT_ID, null))
        // letta-mobile-cyh28: ceil(chars / 4) per section; "hello" is one 5-char text part.
        val expectedSystem = (LocalBackendFixtureStore.SYSTEM_PROMPT.length + 3) / 4
        assertEquals(expectedSystem, context.getValue("num_tokens_system").jsonPrimitive.content.toInt())
        assertEquals(2, context.getValue("num_tokens_messages").jsonPrimitive.content.toInt())
        assertEquals(expectedSystem + 2, context.getValue("context_window_size_current").jsonPrimitive.content.toInt())
        // No record names a window: 0 means unknown, never a made-up 200k.
        assertEquals(0, context.getValue("context_window_size_max").jsonPrimitive.content.toInt())
        assertEquals("estimate", context.getValue("source").jsonPrimitive.content)
        assertEquals("false", context.getValue("calibrated").jsonPrimitive.content)
    }

    @Test
    fun agentContextMatchesTheSectionsToTheReportedTotal() {
        val (store, root) = store()
        File(root, "agents/${LocalBackendFixtureStore.AGENT_ID}.json").writeText(
            """{"id":"${LocalBackendFixtureStore.AGENT_ID}","name":"F","model_settings":{"context_window_limit":200000}}""",
        )
        val context = assertNotNull(store.agentContextProjected(LocalBackendFixtureStore.AGENT_ID, null, reportedTotal = 12_000))
        assertEquals(12_000, context.getValue("context_window_size_current").jsonPrimitive.content.toInt())
        assertEquals(200_000, context.getValue("context_window_size_max").jsonPrimitive.content.toInt())
        val sections = listOf("num_tokens_system", "num_tokens_core_memory", "num_tokens_summary_memory", "num_tokens_messages", "num_tokens_functions_definitions")
            .sumOf { context.getValue(it).jsonPrimitive.content.toInt() }
        assertEquals(12_000, sections)
        assertEquals("true", context.getValue("calibrated").jsonPrimitive.content)
        assertEquals("client", context.getValue("total_source").jsonPrimitive.content)
    }

    @Test
    fun agentContextReadsASessionCompactionAndTheRecordedUsage() {
        val (store, root) = store()
        val dir = LocalBackendFixtureStore.conversationDir(root, LocalBackendFixtureStore.AGENT_ID)
        File(dir, "system-prompt.json").writeText("""{"content":"${"s".repeat(400)}${"m".repeat(400)}","coreMemory":"${"m".repeat(400)}"}""")
        File(dir, "messages.jsonl").writeText(
            listOf(
                """{"type":"message","id":"e1","message":{"id":"m1","role":"user","content":"${"x".repeat(4000)}"}}""",
                """{"type":"compaction","id":"e2","firstKeptEntryId":null,"summary":"s","tokensBefore":0,""" +
                    """"message":{"id":"m2","role":"user","content":"${"y".repeat(40)}","timestamp":5,"metadata":{"compaction":{"summary":"s"}}}}""",
                """{"type":"message","id":"e3","message":{"id":"m3","role":"assistant","content":[{"type":"text","text":"ok"}],"usage":{"totalTokens":9000},"timestamp":9}}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val context = assertNotNull(store.agentContextProjected(LocalBackendFixtureStore.AGENT_ID, null))
        assertEquals(100, context.getValue("num_tokens_system").jsonPrimitive.content.toInt())
        assertEquals(100, context.getValue("num_tokens_core_memory").jsonPrimitive.content.toInt())
        assertEquals(10, context.getValue("num_tokens_summary_memory").jsonPrimitive.content.toInt())
        // The pre-compaction message is gone; only the reply after it is counted.
        assertEquals(1, context.getValue("num_tokens_messages").jsonPrimitive.content.toInt())
        assertEquals(9_000, context.getValue("context_window_size_current").jsonPrimitive.content.toInt())
        assertEquals("recorded", context.getValue("total_source").jsonPrimitive.content)
        assertEquals(2, context.getValue("num_messages").jsonPrimitive.content.toInt())
    }

    @Test
    fun agentContextFallsBackToTheAgentRecordSystemFieldWithoutASidecar() {
        val (store, root) = store()
        File(LocalBackendFixtureStore.conversationDir(root, LocalBackendFixtureStore.AGENT_ID), "system-prompt.json")
            .delete()
        File(root, "agents/${LocalBackendFixtureStore.AGENT_ID}.json").writeText(
            """{"id":"${LocalBackendFixtureStore.AGENT_ID}","name":"F","system":"from record","model_settings":{}}""",
        )
        val context = assertNotNull(store.agentContextProjected(LocalBackendFixtureStore.AGENT_ID, null))
        assertEquals("from record", context.getValue("system_prompt").jsonPrimitive.content)
    }

    @Test
    fun agentContextIsNullForAnUnknownAgentSoTheHandlerFailsClosed() {
        val (store, _) = store()
        assertNull(store.agentContextProjected("agent-unknown", null))
    }

    // ── agent-scoped block.list_agent (kzqkr.7) ──────────────────────────────

    @Test
    fun blocksForAgentReturnsOnlyTheRequestedAgentsRows() {
        val (store, root) = store()
        LocalBackendFixtureStore.writeAgent(root, "agent-2", name = "Second")
        LocalBackendFixtureStore.writeBlock(root, "agent-2", "human", "someone")

        val agent1Blocks = store.blocksForAgentProjected(LocalBackendFixtureStore.AGENT_ID)
        val agent2Blocks = store.blocksForAgentProjected("agent-2")

        assertEquals(
            listOf(LocalBackendFixtureStore.BLOCK_LABEL),
            agent1Blocks.map { it.jsonObject.getValue("label").jsonPrimitive.content },
            "agent-1 should only return its own blocks",
        )
        assertEquals(
            listOf("human"),
            agent2Blocks.map { it.jsonObject.getValue("label").jsonPrimitive.content },
            "agent-2 should only return its own blocks",
        )
        // Both blocks carry stable synthesised ids.
        agent1Blocks.forEach {
            val id = it.jsonObject.getValue("id").jsonPrimitive.content
            assertTrue(id.startsWith("block-"), "every block must carry a synthesised id: $id")
        }
        agent2Blocks.forEach {
            val id = it.jsonObject.getValue("id").jsonPrimitive.content
            assertTrue(id.startsWith("block-"), "every block must carry a synthesised id: $id")
        }
    }

    @Test
    fun blocksForAgentReturnsEmptyForAnAgentWithNoBlocks() {
        val (store, root) = store()
        LocalBackendFixtureStore.writeAgent(root, "agent-noblocks", name = "No Blocks")
        val blocks = store.blocksForAgentProjected("agent-noblocks")
        assertEquals(0, blocks.size)
    }

    @Test
    fun blocksForAgentReturnsEmptyForUnknownAgent() {
        val (store, _) = store()
        val blocks = store.blocksForAgentProjected("agent-unknown")
        assertEquals(0, blocks.size)
    }

    // ── fail-closed on a missing / unreadable store ─────────────────────────

    @Test
    fun everyReaderDegradesToNullOnAMissingStoreRootRatherThanThrowing() {
        val missing = LocalBackendAdminStore(File("/nonexistent/lgns8-9-store"), lmstudioBaseUrl = "http://e/v1")
        assertEquals(0, assertNotNull(missing.listRunsProjected(RunQuery())).size)
        assertNull(missing.getRunProjected("run-1"))
        assertEquals(0, assertNotNull(missing.listBlocksProjected(null, null)).size)
        assertNull(missing.getBlockProjected("block-1"))
        assertNull(missing.agentContextProjected("agent-1", null))
        assertTrue(!missing.runExists("run-1"))
    }
}
