package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AppServerContextWindowPreflightTest {
    @Test
    fun missingLimitIsPersistedAndRecordedOverflowUsesNativeSlidingWindowCompaction() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject { put("model_settings", buildJsonObject { }) },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            stopReason = "length",
                            input = 407_000,
                        ),
                    ),
                ),
            ),
        )
        val ids = ArrayDeque(listOf("agent", "conversation", "update", "messages", "compact"))

        val result = AppServerContextWindowPreflight(
            client = client,
            requestIdFactory = { ids.removeFirst() },
        ).prepare("agent-1", "conv-1")

        assertTrue(result.configuredContextLimit)
        assertTrue(result.compacted)
        assertEquals(200_000, client.updateCommand?.body?.get("context_window_limit")?.jsonPrimitive?.content?.toInt())
        val compact = assertNotNull(client.compactCommand)
        assertEquals("conv-1", compact.conversationId)
        assertEquals("agent-1", compact.body?.get("agent_id")?.jsonPrimitive?.content)
        val settings = compact.body?.get("compaction_settings")?.jsonObject
        assertEquals("sliding_window", settings?.get("mode")?.jsonPrimitive?.content)
        assertEquals("0.3", settings?.get("sliding_window_percentage")?.jsonPrimitive?.content)
        assertEquals("desc", client.messagesCommand?.query?.get("order")?.jsonPrimitive?.content)
        assertEquals("50", client.messagesCommand?.query?.get("limit")?.jsonPrimitive?.content)
    }

    @Test
    fun explicitLimitAndHealthyRecentMessagesArePreserved() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject {
                put(
                    "model_settings",
                    buildJsonObject { put("context_window_limit", 128_000) },
                )
            },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            stopReason = "stop",
                            input = 40_000,
                            output = 300,
                            contentEmpty = false,
                        ),
                    ),
                ),
            ),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertFalse(result.configuredContextLimit)
        assertFalse(result.compacted)
        assertEquals(null, client.updateCommand)
        assertEquals(null, client.compactCommand)
    }

    @Test
    fun emptyLengthResponseCompactsEvenWhenProviderOmitsUsage() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject { put("context_window_limit", 200_000) },
            messages = JsonArray(
                listOf(providerMessage(ProviderMessageFixture(stopReason = "length"))),
            ),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertFalse(result.configuredContextLimit)
        assertTrue(result.compacted)
        assertNotNull(client.compactCommand)
    }

    @Test
    fun lengthStopBelowConfiguredDefaultStillCompacts() = runTest {
        // Provider hit its real 128k boundary with non-empty content while the
        // wrapper default (200k) is persisted — length + near-capacity input
        // (half of configured limit) must still compact.
        val client = PreflightClient(
            agent = buildJsonObject { put("context_window_limit", 200_000) },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            stopReason = "length",
                            input = 128_000,
                            contentEmpty = false,
                        ),
                    ),
                ),
            ),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertTrue(result.compacted)
        assertNotNull(client.compactCommand)
    }

    @Test
    fun maxTokensLengthStopWithLowInputDoesNotCompact() = runTest {
        // Providers emit stop_reason=length for output max_tokens caps even when
        // input context is tiny — that must not start a compaction storm.
        val client = PreflightClient(
            agent = buildJsonObject { put("context_window_limit", 200_000) },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            stopReason = "length",
                            input = 2_000,
                            output = 1_000,
                            contentEmpty = false,
                        ),
                    ),
                ),
            ),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertFalse(result.compacted)
        assertEquals(null, client.compactCommand)
    }

    @Test
    fun olderActiveLengthStopIsIgnoredWhenNewestAssistantIsHealthy() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject { put("context_window_limit", 200_000) },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            id = "newest-ok",
                            stopReason = "stop",
                            input = 40_000,
                            contentEmpty = false,
                        ),
                    ),
                    providerMessage(
                        ProviderMessageFixture(
                            id = "older-length",
                            stopReason = "length",
                            input = 128_000,
                            contentEmpty = false,
                        ),
                    ),
                ),
            ),
            activeMessageIds = listOf("newest-ok", "older-length"),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertFalse(result.compacted)
        assertEquals(null, client.compactCommand)
    }

    @Test
    fun historicalOverflowOutsideActiveContextIsNotCompactedAgain() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject { put("context_window_limit", 200_000) },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            id = "old-overflow",
                            stopReason = "length",
                            input = 407_000,
                        ),
                    ),
                ),
            ),
            activeMessageIds = listOf("summary-1", "recent-user-1"),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertFalse(result.compacted)
        assertEquals(null, client.compactCommand)
    }

    @Test
    fun failedSafetyReadStopsTheTurnPreflight() = runTest {
        val client = PreflightClient(
            agent = null,
            messages = JsonArray(emptyList()),
            retrieveSuccess = false,
        )

        assertFailsWith<IllegalStateException> {
            AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")
        }
        assertEquals(null, client.updateCommand)
        assertEquals(null, client.compactCommand)
    }

    @Test
    fun conversationOverrideDoesNotPersistAgentWideDefault() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject { put("model_settings", buildJsonObject { }) },
            conversation = buildJsonObject {
                put("id", "conv-1")
                put("context_window_limit", 128_000)
                put(
                    "in_context_message_ids",
                    JsonArray(listOf(JsonPrimitive("msg-overflow"))),
                )
            },
            messages = JsonArray(
                listOf(
                    providerMessage(
                        ProviderMessageFixture(
                            stopReason = "stop",
                            input = 40_000,
                            output = 300,
                            contentEmpty = false,
                        ),
                    ),
                ),
            ),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertFalse(result.configuredContextLimit)
        assertFalse(result.compacted)
        assertEquals(null, client.updateCommand)
    }

    @Test
    fun appServerShapedEmptyAssistantWithoutRoleTriggersCompaction() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject {
                put("model_settings", buildJsonObject { put("context_window_limit", 200_000) })
            },
            messages = JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "msg-empty-asst")
                        put("message_type", "assistant_message")
                        put("content", "")
                    },
                ),
            ),
            activeMessageIds = listOf("msg-empty-asst"),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertTrue(result.compacted)
        assertNotNull(client.compactCommand)
    }

    @Test
    fun blankStringAssistantContentIsTreatedAsPoisoned() = runTest {
        val client = PreflightClient(
            agent = buildJsonObject {
                put("model_settings", buildJsonObject { put("context_window_limit", 200_000) })
            },
            messages = JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "msg-blank")
                        put("role", "assistant")
                        put("content", "   ")
                    },
                ),
            ),
            activeMessageIds = listOf("msg-blank"),
        )

        val result = AppServerContextWindowPreflight(client).prepare("agent-1", "conv-1")

        assertTrue(result.compacted)
    }

    private fun providerMessage(fixture: ProviderMessageFixture) = buildJsonObject {
        put("id", fixture.id)
        put("role", "assistant")
        put(
            "content",
            if (fixture.contentEmpty) JsonArray(emptyList())
            else JsonArray(listOf(buildJsonObject { put("text", "ok") })),
        )
        put(
            "provider_result",
            buildJsonObject {
                put("stopReason", fixture.stopReason)
                put(
                    "usage",
                    buildJsonObject {
                        fixture.input?.let { put("input", it) }
                        put("output", fixture.output)
                    },
                )
            },
        )
    }

    private data class ProviderMessageFixture(
        val id: String = "msg-overflow",
        val stopReason: String,
        val input: Int? = null,
        val output: Int = 1,
        val contentEmpty: Boolean = true,
    )
}

private class PreflightClient(
    private val agent: kotlinx.serialization.json.JsonObject?,
    private val messages: JsonArray,
    private val retrieveSuccess: Boolean = true,
    private val activeMessageIds: List<String> = listOf("msg-overflow"),
    private val conversation: kotlinx.serialization.json.JsonObject? = null,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = emptyFlow()
    var updateCommand: AppServerCommand.AgentUpdate? = null
    var messagesCommand: AppServerCommand.ConversationMessagesList? = null
    var compactCommand: AppServerCommand.ConversationCompact? = null

    override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve) =
        AppServerInboundFrame.AgentRetrieveResponse(command.requestId, retrieveSuccess, agent, "retrieve failed")

    override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate): AppServerInboundFrame.AgentUpdateResponse {
        updateCommand = command
        return AppServerInboundFrame.AgentUpdateResponse(command.requestId, true)
    }

    override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) =
        AppServerInboundFrame.ConversationRetrieveResponse(
            requestId = command.requestId,
            success = true,
            conversation = conversation ?: buildJsonObject {
                put("id", command.conversationId)
                put(
                    "in_context_message_ids",
                    JsonArray(activeMessageIds.map(::JsonPrimitive)),
                )
            },
        )

    override suspend fun conversationMessagesList(
        command: AppServerCommand.ConversationMessagesList,
    ): AppServerInboundFrame.ConversationMessagesListResponse {
        messagesCommand = command
        return AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, true, messages)
    }

    override suspend fun conversationCompact(
        command: AppServerCommand.ConversationCompact,
    ): AppServerInboundFrame.ConversationCompactResponse {
        compactCommand = command
        return AppServerInboundFrame.ConversationCompactResponse(command.requestId, true)
    }

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        error("unused")

    override suspend fun input(command: AppServerCommand.Input) = error("unused")
    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("unused")
    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = error("unused")
    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = error("unused")
    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
}
