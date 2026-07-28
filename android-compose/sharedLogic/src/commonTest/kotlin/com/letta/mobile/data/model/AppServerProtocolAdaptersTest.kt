package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AppServerSubagentSnapshotAdapterTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun pendingWithoutToolCallIdUsesSubagentIdAndAppearsActive() {
        val raw = json.parseToJsonElement(
            """
            {
              "subagent_id": "sa-pending-1",
              "status": "pending",
              "conversation_id": "sub-conv-1",
              "start_time": 1700000000
            }
            """.trimIndent(),
        ).jsonObject

        val entry = AppServerSubagentSnapshotAdapter.toEntry(raw, "parent-conv", "parent-agent")
        assertEquals("sa-pending-1", entry!!.toolCallId)
        assertEquals(SubagentStatus.RUNNING, entry.status)
        assertEquals("sub-conv-1", entry.subagentConversationId)
        assertEquals("1700000000000", entry.startedAt)
    }

    @Test
    fun errorStatusMapsToFailedTerminal() {
        val raw = json.parseToJsonElement(
            """
            {
              "tool_call_id": "tool/err",
              "status": "error",
              "error": "boom",
              "conversation_id": "sub-conv-err",
              "start_time": 1700000000000
            }
            """.trimIndent(),
        ).jsonObject

        val entry = AppServerSubagentSnapshotAdapter.toEntry(raw, "parent-conv", null)
        assertEquals(SubagentStatus.FAILED, entry!!.status)
        assertEquals("sub-conv-err", entry.subagentConversationId)
        assertEquals("1700000000000", entry.startedAt)
    }

    @Test
    fun toolCallIdPreferredOverSubagentIdForStableIdentity() {
        val raw = buildJsonObject {
            put("subagent_id", "sa-1")
            put("tool_call_id", "tool/1")
            put("status", "running")
        }
        val entry = AppServerSubagentSnapshotAdapter.toEntry(raw, "c", "a")
        assertEquals("tool/1", entry!!.toolCallId)
    }
}

class AppServerListModelsAdapterTest {
    @Test
    fun mapsExactPresentationEntryIntoDisplayProviderAndSelectionPayload() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "model-1")
                    put("handle", "anthropic/claude-fable-5")
                    put("label", "Claude Fable 5")
                    put("description", "presentation only")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "anthropic/claude-fable-5")
                        },
                    )
                },
            ),
        )
        val decoded = AppServerListModelsAdapter.decodeEntries(entries).single()
        val model = AppServerListModelsAdapter.toLlmModel(decoded)
        assertEquals("Claude Fable 5", model.displayName)
        assertEquals("anthropic", model.providerType)
        assertEquals("anthropic/claude-fable-5", AppServerListModelsAdapter.selectionHandle(decoded))
        assertNull(model.contextWindow)
        assertNull(model.enableReasoner)

        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        assertEquals("Claude Fable 5", adapted["display_name"]!!.jsonPrimitive.content)
        assertEquals("anthropic/claude-fable-5", adapted["selection_handle"]!!.jsonPrimitive.content)
        assertEquals("presentation only", adapted["description"]!!.jsonPrimitive.content)
        assertTrue(adapted["updateArgs"] != null)
        assertNull(adapted["context_window"])

        // Idempotent when the client re-adapts the server-adapted payload.
        val roundTrip = AppServerListModelsAdapter.toLlmModels(JsonArray(listOf(adapted))).single()
        assertEquals("Claude Fable 5", roundTrip.displayName)
        assertEquals("anthropic", roundTrip.providerType)
        assertNull(roundTrip.contextWindow)
        assertNull(roundTrip.enableReasoner)
    }

    @Test
    fun consumesSelectionHandleWhenPresentationAliasDiffers() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "model-1")
                    put("handle", "display-alias")
                    put("label", "Display Alias")
                    put("selection_handle", "openai/target-model")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/target-model")
                        },
                    )
                },
            ),
        )
        val model = AppServerListModelsAdapter.toLlmModels(entries).single()
        assertEquals("openai/target-model", model.handle)
        assertEquals("Display Alias", model.displayName)
        assertEquals("openai", model.providerType)
    }
}
