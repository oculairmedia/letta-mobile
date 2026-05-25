package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeContractsTest {
    private val json = Json {
        encodeDefaults = true
    }

    @Test
    fun runtimeEventEnvelopeRoundTripsToolPayload() {
        val event = RuntimeEventEnvelope(
            offset = RuntimeEventOffset(12),
            eventId = RuntimeEventId("event-12"),
            backendId = BackendId("remote-default"),
            runtimeId = RuntimeId("runtime-default"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conversation-1"),
            runId = RunId("run-1"),
            createdAt = EpochMillis(1_700_000_000_000),
            source = RuntimeEventSource.RemoteLetta,
            payload = RuntimeEventPayload.ToolCallObserved(
                toolCallId = ToolCallId("call-1"),
                toolName = ToolName("read_file"),
                argumentsJson = """{"path":"/notes.md"}""",
            ),
        )

        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<RuntimeEventEnvelope>(encoded)

        assertEquals(event, decoded)
        assertTrue(encoded.contains("tool_call_observed"))
    }

    @Test
    fun memFsPathsMustBeAbsolute() {
        assertEquals("/memory/core.md", MemFsPath("/memory/core.md").value)
        assertFailsWith<IllegalArgumentException> {
            MemFsPath("memory/core.md")
        }
        assertFailsWith<IllegalArgumentException> {
            MemFsPath("/memory//core.md")
        }
    }

    @Test
    fun memFsCommitPayloadRoundTrips() {
        val event = RuntimeEventEnvelope(
            offset = RuntimeEventOffset(1),
            eventId = RuntimeEventId("event-1"),
            backendId = BackendId("local"),
            runtimeId = RuntimeId("koog"),
            createdAt = EpochMillis(1_700_000_000_001),
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.MemFsCommitObserved(
                commit = MemFsCommit(
                    id = MemFsCommitId("commit-1"),
                    revision = MemFsRevision(2),
                    path = MemFsPath("/memory/profile.md"),
                    operation = MemFsOperation.Write,
                    createdAt = EpochMillis(1_700_000_000_001),
                ),
            ),
        )

        assertEquals(event, json.decodeFromString<RuntimeEventEnvelope>(json.encodeToString(event)))
    }

    @Test
    fun turnCommandRoundTripsWithToolPolicyAndAttachments() {
        val command = TurnCommand(
            backendId = BackendId("local"),
            runtimeId = RuntimeId("koog"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conversation-1"),
            input = TurnInput.UserMessage(
                localMessageId = "local-message-1",
                text = "summarize the attached file",
                attachments = listOf(AgentFileId("agent-file-1")),
            ),
            memFsRevision = MemFsRevision(4),
            toolPolicy = ToolPolicy(
                approvalMode = ToolApprovalMode.RequireForEveryCall,
                allowedTools = setOf(ToolName("read_file")),
                deniedTools = setOf(ToolName("shell")),
            ),
            metadata = mapOf("surface" to "chat"),
        )

        val encoded = json.encodeToString(command)
        val decoded = json.decodeFromString<TurnCommand>(encoded)

        assertEquals(command, decoded)
        assertTrue(encoded.contains("user_message"))
    }

    @Test
    fun approvalPayloadsUseSharedDecisionContracts() {
        val request = ToolApprovalRequest(
            approvalId = ToolApprovalId("approval-1"),
            callId = ToolCallId("call-1"),
            toolName = ToolName("shell"),
            prompt = "Allow shell command?",
            argumentsPreview = "ls",
        )
        val decision = ToolApprovalDecision(
            approvalId = request.approvalId,
            callId = request.callId,
            decision = ToolApprovalDecisionValue.Denied,
            scope = ToolApprovalScope.Once,
            response = "not needed",
        )

        val requested = RuntimeEventPayload.ApprovalRequested(request)
        val resolved = RuntimeEventPayload.ApprovalResolved(decision)

        assertEquals(request, json.decodeFromString<RuntimeEventPayload>(json.encodeToString<RuntimeEventPayload>(requested)).let {
            (it as RuntimeEventPayload.ApprovalRequested).request
        })
        assertEquals(decision, json.decodeFromString<RuntimeEventPayload>(json.encodeToString<RuntimeEventPayload>(resolved)).let {
            (it as RuntimeEventPayload.ApprovalResolved).decision
        })
    }
}
