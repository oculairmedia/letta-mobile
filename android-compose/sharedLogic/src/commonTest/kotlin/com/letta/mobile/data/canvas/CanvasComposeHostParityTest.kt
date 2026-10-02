package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.AGENT
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.CONVERSATION
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.WEEKEND_PLAN
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.receipt
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The same canvas_compose call answered by the Iroh host and by an app's own App Server
 * (letta-mobile-bglj6.12) publishes the same ops, leaves the same board and answers the same
 * receipt: both go through CanvasComposeService and write one [CanvasStampedBatch]. Only the op
 * ids differ (each host mints its own) and the revision (each counts its own way); the receipts
 * are compared with the revision each host reports, which is also what its get_scene answers.
 */
class CanvasComposeHostParityTest {
    private val canvasId = CanvasId.forConversation(CONVERSATION)

    private suspend fun ExternalToolRegistry.compose(request: String = WEEKEND_PLAN, toolCallId: String = TOOL_CALL) =
        invoke(CanvasToolContract.COMPOSE, Json.parseToJsonElement(request).jsonObject, ExternalToolCaller(AGENT, CONVERSATION, toolCallId))

    /** The app's canvas as the host's: the conversation's, empty, the agent its writer. */
    private suspend fun InMemoryCanvasDocumentStore.conversationCanvas(revision: Long) = upsert(
        CanvasDocument(
            id = canvasId, agentId = AGENT, conversationId = CONVERSATION, title = "Conversation canvas",
            revision = revision, sceneJson = "", acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(AGENT)),
            updatedAtEpochMs = 0L,
        ),
    )

    @Test
    fun theHostAndTheAppPublishTheSameArtifact() = runTest {
        for (request in listOf(WEEKEND_PLAN, NO_ARTIFACT_ID)) {
            // The Iroh host, through the relay's log.
            val host = HostCanvasComposeToolsTest.Host()
            val hostReceipt = receipt(host.registry.compose(request))
            val hostBatch = assertIs<CanvasOp.BatchOp>(host.logged().single().op)
            val hostScene = host.scene().sceneJson

            // An app with the board open.
            val sessionStore = InMemoryCanvasDocumentStore()
            val sessions = CanvasSessionRegistry()
            val session = CanvasSession.create(
                sessionStore,
                CanvasCreateOptions(conversationId = CONVERSATION, agentId = AGENT, canvasId = canvasId),
            ).also(sessions::register)
            val sessionReceipt = receipt(ExternalToolRegistry.hostTools(CanvasExternalTools.all(sessionStore, sessions)).compose(request))
            val sessionBatch = assertIs<CanvasOp.BatchOp>(session.opLog.getOps(canvasId).single())

            // An app with the board closed, its canvas at the host's revision.
            val closedStore = InMemoryCanvasDocumentStore().apply { conversationCanvas(revision = 0L) }
            val closedReceipt = receipt(ExternalToolRegistry.hostTools(CanvasExternalTools.all(closedStore, CanvasSessionRegistry())).compose(request))
            val closedScene = closedStore.get(canvasId)!!.sceneJson

            assertEquals(hostReceipt, closedReceipt, "the receipt, byte for byte, at the same revision")
            assertEquals(hostReceipt.copy(revision = sessionReceipt.revision), sessionReceipt)
            assertEquals(unstamped(hostBatch), unstamped(sessionBatch), "the same ops, in the same order, with the same clocks")
            assertEquals(withoutOpIds(hostScene), withoutOpIds(session.document.value!!.sceneJson))
            assertEquals(withoutOpIds(hostScene), withoutOpIds(closedScene))
        }
    }

    private fun unstamped(batch: CanvasOp.BatchOp): List<CanvasOp> = batch.ops.map { it.withStamp("", it.lamport) }

    /** A scene with every op id dropped: the ids each host mints are the one thing that differs. */
    private fun withoutOpIds(sceneJson: String): JsonElement = strip(Json.parseToJsonElement(sceneJson))

    private fun strip(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { !it.lowercase().contains("opid") }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(element.map(::strip))
        else -> element
    }

    private companion object {
        const val TOOL_CALL = "toolu_parity_1"
        const val NO_ARTIFACT_ID = """{"title":"Errands","items":[{"kind":"CHECKLIST","items":[{"text":"Post office"}]},{"kind":"NOTE","markdown":"Call **Sam**"}]}"""
    }
}
