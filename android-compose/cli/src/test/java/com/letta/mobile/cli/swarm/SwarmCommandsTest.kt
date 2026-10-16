package com.letta.mobile.cli.swarm

import com.github.ajalt.clikt.testing.test
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.swarm.AdminOutcome
import com.letta.mobile.data.swarm.ClientMessageId
import com.letta.mobile.data.swarm.ConversationTarget
import com.letta.mobile.data.swarm.DeliveryFailure
import com.letta.mobile.data.swarm.DeliveryReceipt
import com.letta.mobile.data.swarm.DeliveryStage
import com.letta.mobile.data.swarm.InputDisposition
import com.letta.mobile.data.swarm.LinkFailure
import com.letta.mobile.data.swarm.LinkResult
import com.letta.mobile.data.swarm.OutboxEntry
import com.letta.mobile.data.swarm.SwarmLink
import com.letta.mobile.data.swarm.SwarmMessenger
import com.letta.mobile.data.swarm.SwarmOutbox
import com.letta.mobile.data.swarm.SwarmReply
import com.letta.mobile.data.swarm.SwarmSession
import com.letta.mobile.data.swarm.TurnTerminal
import com.letta.mobile.data.transport.appserver.AppServerAdminMethod
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-jvctq: the swarm commands as the orchestrator drives them: argument parsing, the
 * distinct exit codes (approval-required in particular), JSON receipts, pairing guards. The shared
 * messenger runs for real; only the link (a scripted session) is fake.
 */
class SwarmCommandsTest {
    @TempDir
    lateinit var profileDir: Path

    @Test
    fun sendParsesTargetsWaitStagesSenderAndIds() {
        val send = MessageSendCommand(environment())
        send.test(listOf("--agent", "PM-letta-mobile", "--text", "hi", "--from", "orch\nestrator", "--wait", "accepted", "--client-message-id", "swarm-7"))

        val request = send.request()
        assertEquals(ConversationTarget.New, request.target, "orchestrator traffic defaults to a new conversation")
        assertEquals(DeliveryStage.Accepted, request.waitFor)
        assertEquals("orchestrator", request.from?.value)
        assertEquals(ClientMessageId("swarm-7"), request.clientMessageId)
        assertEquals(300_000.milliseconds, request.timeout)
    }

    @Test
    fun sendRefusesConflictingOrMissingInput() {
        val both = MessageSendCommand(environment()).test(listOf("--agent", "pm", "--conversation", "c-1", "--new", "--text", "hi"))
        val twoBodies = MessageSendCommand(environment()).test(listOf("--agent", "pm", "--text", "hi", "--stdin"))
        val noBody = MessageSendCommand(environment()).test(listOf("--agent", "pm"))
        val badId = MessageSendCommand(environment()).test(listOf("--agent", "pm", "--text", "hi", "--client-message-id", "../x"))

        listOf(both, twoBodies, noBody, badId).forEach { result -> assertEquals(1, result.statusCode, result.stderr) }
    }

    @Test
    fun sendReadsStdinWithinTheSizeBound() {
        val ok = MessageSendCommand(environment(stdin = "from stdin")).apply { test(listOf("--agent", "pm", "--stdin")) }
        assertEquals("from stdin", ok.request().text)

        val tooLong = MessageSendCommand(environment(stdin = "x".repeat(40_000))).test(listOf("--agent", "pm", "--stdin"))
        assertEquals(1, tooLong.statusCode)
    }

    @Test
    fun completedTurnPrintsJsonReceiptsAndExitsZero() {
        val result = MessageSendCommand(environment(drafts = listOf(lifecycle(RuntimeRunStatus.Completed))))
            .test(listOf("--agent", "pm-letta-mobile", "--conversation", CONVERSATION, "--text", "hi", "--output", "json"))

        assertEquals(0, result.statusCode, result.output)
        val lines = result.stdout.trim().lines()
        assertTrue(lines.first().contains("\"event\":\"connected\""), result.stdout)
        assertTrue(lines.last().contains("\"event\":\"processed\"") && lines.last().contains("\"terminal\":\"completed\""), result.stdout)
    }

    @Test
    fun approvalRequiredExitsWithItsOwnCodeAndNeverAnswers() {
        val session = ScriptedSession(listOf(approvalRequested()))
        val result = MessageSendCommand(environment(session = session))
            .test(listOf("--agent", "pm-letta-mobile", "--conversation", CONVERSATION, "--text", "deploy"))

        assertEquals(SwarmExit.NeedsApproval.code, result.statusCode)
        assertTrue(result.stderr.contains("approval needed: Bash"), result.stderr)
        assertTrue(result.stdout.contains("needs approval"), result.stdout)
        assertTrue(session.adminMethods.none { it.startsWith("approval") }, "no approval is answered")
    }

    @Test
    fun deniedDeviceAndOutOfScopeConversationHaveDistinctCodes() {
        val denied = MessageSendCommand(environment(link = FailingLink(LinkFailure.Denied("revoked"))))
            .test(listOf("--agent", "pm", "--text", "hi"))
        val outOfScope = MessageSendCommand(environment())
            .test(listOf("--agent", "pm-letta-mobile", "--conversation", "conversation-of-someone-else", "--text", "hi"))

        assertEquals(SwarmExit.Denied.code, denied.statusCode)
        assertEquals(SwarmExit.ConversationRefused.code, outOfScope.statusCode)
    }

    @Test
    fun agentsListPrintsTheScopedAgents() {
        val result = AgentsListCommand(environment()).test(listOf("--output", "json"))

        assertEquals(0, result.statusCode)
        assertEquals("""[{"id":"agent-pm","name":"PM-letta-mobile"}]""", result.stdout.trim())
    }

    @Test
    fun readShowsHostSenderMetadata() {
        val result = MessageReadCommand(environment())
            .test(listOf("--agent", "agent-pm", "--conversation", CONVERSATION))

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("sender=device:phone") && result.stdout.contains("hello"), result.stdout)
    }

    @Test
    fun pairFromStdinRequiresYesAndRefusesAnInvalidCode() {
        val noYes = SwarmPairCommand(environment(stdin = "secret")).test(listOf("--invite", "-"))
        val invalid = SwarmPairCommand(environment(stdin = "not-an-invite")).test(listOf("--invite", "-", "--yes"))

        assertEquals(1, noYes.statusCode)
        assertEquals(SwarmExit.InvalidInvite.code, invalid.statusCode)
        assertTrue(!invalid.output.contains("not-an-invite"), "the invite is never echoed")
    }

    @Test
    fun exitCodesAreDistinctPerOutcome() {
        val codes = SwarmExit.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(SwarmExit.Ok, sendExit(DeliveryReceipt.Accepted(ClientMessageId("swarm-1"), InputDisposition.Queued)))
        assertEquals(SwarmExit.NeedsApproval, sendExit(DeliveryReceipt.Processed(TurnTerminal.NeedsApproval(emptyList()), SwarmReply.Empty)))
        assertEquals(SwarmExit.ReplyUnobserved, sendExit(DeliveryReceipt.Processed(TurnTerminal.Unobserved, SwarmReply.Empty)))
        assertEquals(SwarmExit.Unconfirmed, sendExit(DeliveryFailure.Unconfirmed(ClientMessageId("swarm-1"))))
    }

    @Test
    fun profileLivesUnderTheUserProfile() {
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Roaming", "Meridian", "cli"), defaultSwarmProfileDirectory(mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming"), "C:\\Users\\me"))
        assertEquals(Path.of("/home/me", ".letta", "meridian-cli"), defaultSwarmProfileDirectory(emptyMap(), "/home/me"))
    }

    private fun environment(
        drafts: List<RuntimeEventDraft> = listOf(lifecycle(RuntimeRunStatus.Completed)),
        session: ScriptedSession = ScriptedSession(drafts),
        link: SwarmLink = SessionLink(session),
        stdin: String = "",
    ) = SwarmCliEnvironment(
        messenger = { SwarmMessenger(link, MemoryOutbox()) },
        redeemer = { PairingRedeemer { _, _ -> RedeemOutcome.Failed(PairingError.DENIED) } },
        stdin = { ByteArrayInputStream(stdin.toByteArray()) },
        readLine = { null },
        nowMs = { 1_000L },
        defaultProfileDirectory = { profileDir },
    )

    private class ScriptedSession(private val drafts: List<RuntimeEventDraft>) : SwarmSession {
        val adminMethods = mutableListOf<String>()
        // Like the real engine, the turn stays open after its drafts (a parked approval never ends the stream).
        override val turnEngine: TurnEngine = TurnEngine {
            flow {
                drafts.forEach { emit(it) }
                awaitCancellation()
            }
        }
        override val connected: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun admin(method: AppServerAdminMethod, params: JsonObject): AdminOutcome {
            adminMethods += method.value
            return when (method.value) {
                "agent.list" -> AdminOutcome.Ok(
                    if ((params["offset"] as? JsonPrimitive)?.contentOrNull == "0") JsonArray(listOf(agentJson)) else JsonArray(emptyList()),
                )
                "conversation.get" -> conversation((params["conversation_id"] as? JsonPrimitive)?.contentOrNull)
                "conversation.create" -> AdminOutcome.Ok(buildJsonObject { put("id", "conversation-new"); put("agent_id", PM) })
                "message.list" -> AdminOutcome.Ok(JsonArray(listOf(phoneMessage)))
                else -> AdminOutcome.Failed("unexpected ${method.value}")
            }
        }

        private fun conversation(id: String?): AdminOutcome = AdminOutcome.Ok(
            buildJsonObject {
                put("id", id)
                put("agent_id", if (id == CONVERSATION) PM else "agent-other")
            },
        )
    }

    private class SessionLink(private val session: SwarmSession) : SwarmLink {
        override suspend fun <T> withSession(block: suspend (SwarmSession) -> T): LinkResult<T> = LinkResult.Ok(block(session))
    }

    private class FailingLink(private val failure: LinkFailure) : SwarmLink {
        override suspend fun <T> withSession(block: suspend (SwarmSession) -> T): LinkResult<T> = LinkResult.Failed(failure)
    }

    private class MemoryOutbox : SwarmOutbox {
        private val entries = linkedMapOf<ClientMessageId, OutboxEntry>()
        override suspend fun get(id: ClientMessageId) = entries[id]
        override suspend fun put(entry: OutboxEntry) { entries[entry.id] = entry }
        override suspend fun remove(id: ClientMessageId) { entries.remove(id) }
        override suspend fun list() = entries.values.toList()
    }

    private companion object {
        const val PM = "agent-pm"
        const val CONVERSATION = "conversation-pm"
        val agentJson = buildJsonObject { put("id", PM); put("name", "PM-letta-mobile") }
        val phoneMessage = buildJsonObject {
            put("id", "message-1")
            put("message_type", "user_message")
            put("content", "hello")
            put("sender_id", "device:phone")
        }

        fun draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("swarm"),
            runtimeId = RuntimeId("swarm:$CONVERSATION"),
            source = RuntimeEventSource.RemoteLetta,
            payload = payload,
        )

        fun lifecycle(status: RuntimeRunStatus) = draft(RuntimeEventPayload.RunLifecycleChanged(status))

        fun approvalRequested() = draft(
            RuntimeEventPayload.ApprovalRequested(
                ToolApprovalRequest(
                    approvalId = ToolApprovalId("approval-1"),
                    callId = ToolCallId("call-1"),
                    toolName = ToolName("Bash"),
                    prompt = "run git push?",
                ),
            ),
        )

    }
}
