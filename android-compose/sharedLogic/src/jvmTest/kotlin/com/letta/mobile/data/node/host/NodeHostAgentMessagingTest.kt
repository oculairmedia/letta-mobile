package com.letta.mobile.data.node.host

import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.IrohCliPaths
import com.letta.mobile.data.controller.extras.IrohCliSendResult
import com.letta.mobile.data.controller.extras.messaging.IrohMessageOptions
import com.letta.mobile.data.transport.iroh.AgentSendResult
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Agent-to-agent send per OS: Windows sends in this process, so no `meridian` binary is needed. */
class NodeHostAgentMessagingTest {
    private val sent = mutableListOf<Pair<IrohAgentMessage, IrohCliPaths>>()

    private fun runner(outcome: (IrohAgentMessage) -> AgentSendResult) = InProcessAgentMessageRunner(
        deliver = { message, paths -> sent += message to paths; outcome(message) },
        clock = { 1_234L },
    )

    @Test
    fun windowsOffersTheToolWithNoBinaryAndLinuxOnlyWithOne() {
        val windows = assertNotNull(WindowsNodeBindings.agentMessaging(""))

        assertIs<InProcessAgentMessageRunner>(windows.runner)
        assertNull(LinuxNodeBindings.agentMessaging(""))
        assertEquals("/usr/local/bin/meridian", LinuxNodeBindings.agentMessaging("/usr/local/bin/meridian")?.binary)
        assertNull(LinuxNodeBindings.agentMessaging("/usr/local/bin/meridian")?.runner)
    }

    @Test
    fun theToolSendsInProcessWithTheEnvelopeTheCliWouldBuild() = runBlocking {
        val tool = CustomIrohMessagingTool(
            binary = "",
            identityDir = "ids",
            addressStore = "book.kv",
            runner = runner { AgentSendResult.Delivered(it.msgId) },
        )

        val result = tool.invoke(
            buildJsonObject {
                put("to", "agent-b")
                put("body", "line one\nline \"two\"")
                put("msg_id", "msg-fixed")
                put("conversation_id", "conv-b")
                put("lane", "home")
            },
            "agent-a",
        )

        val (message, paths) = sent.single()
        assertIs<ExternalToolResult.Success>(result)
        assertTrue(result.content.contains("\"delivered\":true"), result.content)
        assertEquals(
            IrohAgentMessage("agent-a", "agent-b", "line one\nline \"two\"", "msg-fixed", 1_234L, conversationId = "conv-b"),
            message,
        )
        assertEquals("ids", paths.identityDir)
        assertEquals("book.kv", paths.addressStore)
    }

    @Test
    fun everyDeliveryOutcomeMapsOneForOneAndFailuresNeverThrow() = runBlocking {
        val outcomes = listOf(
            AgentSendResult.Delivered("m") to IrohCliSendResult.Delivered("m"),
            AgentSendResult.Accepted("m", "agent-b", "home_lane_forbidden") to
                IrohCliSendResult.Accepted("m", "agent-b", "home_lane_forbidden"),
            AgentSendResult.Unaddressable("agent-b", "unknown_host") to IrohCliSendResult.Unaddressable("agent-b", "unknown_host"),
            AgentSendResult.Failed("agent-b", "no_ack") to IrohCliSendResult.Failed("agent-b", "no_ack"),
        )

        val paths = IrohCliPaths(message = IrohMessageOptions(msgId = "m"))
        val mapped = outcomes.map { (outcome, _) -> runner { outcome }.send("", "agent-a", "agent-b", "hi", paths) }
        val thrown = runner { error("boom") }.send("", "agent-a", "agent-b", "hi")

        assertEquals(outcomes.map { it.second }, mapped)
        assertEquals(IrohCliSendResult.Failed("agent-b", "in_process_send_failed: boom"), thrown)
    }

    @Test
    fun anUnaddressableRecipientIsReportedWithoutBindingAnEndpoint() = runBlocking {
        val dir = Files.createTempDirectory("a2a-in-process")
        try {
            val paths = IrohCliPaths(identityDir = dir.resolve("ids").toString(), addressStore = dir.resolve("empty.kv").toString())

            val result = InProcessAgentMessageRunner().send("", "agent-a", "agent-b", "hi", paths)

            assertIs<IrohCliSendResult.Unaddressable>(result)
            assertEquals("agent-b", result.toAgentId)
            // Resolution failed first, so no sender identity was ever created.
            assertTrue(Files.notExists(dir.resolve("ids")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
