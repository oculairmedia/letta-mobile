package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.receipt
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.refusal
import com.letta.mobile.data.canvas.compose.CanvasComposeGuide
import com.letta.mobile.data.canvas.compose.CanvasComposeIds
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeErrorCode
import com.letta.mobile.data.canvas.compose.ComposeProblemCode
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * canvas_compose on an app's own App Server (letta-mobile-bglj6.12): the same matrix as the Iroh
 * host's, through [CanvasExternalTools], with the board open in a live session and without one.
 */
class CanvasComposeToolTest {
    private lateinit var store: InMemoryCanvasDocumentStore
    private lateinit var sessions: CanvasSessionRegistry
    private lateinit var registry: ExternalToolRegistry

    private val canvasId = CanvasId.forConversation(CONVERSATION)

    @BeforeTest
    fun setUp() {
        store = InMemoryCanvasDocumentStore()
        sessions = CanvasSessionRegistry()
        registry = ExternalToolRegistry.hostTools(CanvasExternalTools.all(store, sessions))
    }

    private class RecordingTransport : CanvasSyncTransport {
        val published = mutableListOf<CanvasOp>()
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
            published += op
        }

        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = emptyFlow()
    }

    private suspend fun conversationCanvas(revision: Long = 1L) = store.upsert(
        CanvasDocument(
            id = canvasId, agentId = AGENT, conversationId = CONVERSATION, title = "Conversation canvas",
            revision = revision, sceneJson = "", acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(AGENT)),
            updatedAtEpochMs = 0L,
        ),
    )

    private suspend fun openSession(transport: CanvasSyncTransport? = null): CanvasSession = CanvasSession.create(
        store,
        CanvasCreateOptions(
            title = "Conversation canvas", conversationId = CONVERSATION, agentId = AGENT, canvasId = canvasId,
            syncTransport = transport,
        ),
    ).also(sessions::register)

    private suspend fun compose(request: String, agent: String? = AGENT, conversation: String? = CONVERSATION, toolCallId: String? = "call-1") =
        registry.invoke(CanvasToolContract.COMPOSE, Json.parseToJsonElement(request).jsonObject, agent, conversation, toolCallId)

    private suspend fun revision(): Long = Json { ignoreUnknownKeys = true }.decodeFromString<CanvasGetSceneResult>(
        assertIs<ExternalToolResult.Success>(
            registry.invoke(CanvasToolContract.GET_SCENE, JsonObject(mapOf("canvas_id" to kotlinx.serialization.json.JsonPrimitive(canvasId.value))), AGENT),
        ).content,
    ).revision

    @Test
    fun theAppAdvertisesComposeAndItsGuide() {
        val names = registry.listAdvertisedTools().map { it.name }
        assertTrue(CanvasToolContract.COMPOSE in names)
        assertTrue(CanvasToolContract.COMPOSE_GUIDE in names)
    }

    @Test
    fun withoutASessionTheArtifactIsStoredAsOneRevision() = runTest {
        conversationCanvas()
        val receipt = receipt(compose(WEEKEND_PLAN))
        assertEquals(ComposeStatus.PUBLISHED, receipt.status)
        assertEquals(2L, receipt.revision)
        assertEquals(revision(), receipt.revision)
        val sceneJson = store.get(canvasId)!!.sceneJson
        CanvasOpProjector.documentsOf(sceneJson).filter { it.compose != null }.also { assertTrue(it.isNotEmpty()) }.forEach {
            assertEquals(CanvasGeometryOwner.AUTO, it.owner)
            assertEquals("weekend-plan", it.compose!!.artifactId)
        }
    }

    @Test
    fun withALiveSessionTheArtifactIsOneOpOneRevisionAndOneMessageToPeers() = runTest {
        val transport = RecordingTransport()
        val session = openSession(transport)
        val before = session.document.value!!.revision

        val receipt = receipt(compose(WEEKEND_PLAN))

        assertEquals(before + 1, receipt.revision)
        assertEquals(receipt.revision, session.document.value!!.revision)
        assertEquals(revision(), receipt.revision)
        val batch = assertIs<CanvasOp.BatchOp>(session.opLog.getOps(canvasId).single())
        assertEquals(AGENT, batch.actorId)
        assertTrue(batch.ops.all { it.actorId == AGENT })
        assertEquals(listOf<CanvasOp>(batch), transport.published)
    }

    @Test
    fun theConversationsCanvasIsTheDefaultAndNoneIsNotFound() = runTest {
        assertEquals(ComposeErrorCode.CANVAS_NOT_FOUND, refusal(compose(WEEKEND_PLAN)).code)
        assertEquals(
            ComposeErrorCode.CANVAS_NOT_FOUND,
            refusal(compose("""{"canvas_id":"canvas-nope","items":[{"kind":"NOTE","markdown":"x"}]}""")).code,
        )
        conversationCanvas()
        assertEquals(canvasId.value, receipt(compose(WEEKEND_PLAN)).canvasId)
    }

    @Test
    fun aDryRunWritesNothing() = runTest {
        conversationCanvas()
        val request = WEEKEND_PLAN.replaceFirst("{", """{"dry_run":"true",""")
        val receipt = receipt(compose(request))
        assertEquals(ComposeStatus.DRY_RUN, receipt.status)
        assertNull(receipt.revision)
        assertEquals(1L, store.get(canvasId)!!.revision)
    }

    @Test
    fun aRefusalIsStructuredAndWritesNothing() = runTest {
        val session = openSession()
        val refusal = refusal(compose("""{"items":[{"kind":"POEM","text":"x"}]}"""))
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertTrue(refusal.problems.any { it.path == "/items/0/kind" && it.code == ComposeProblemCode.UNKNOWN_KIND.name })
        assertEquals(1L, session.document.value!!.revision)
        assertTrue(session.opLog.getOps(canvasId).isEmpty())
    }

    @Test
    fun aRetryAnswersTheSameReceiptAndWritesNothing() = runTest {
        val session = openSession()
        val first = receipt(compose(WEEKEND_PLAN, toolCallId = "call-1"))
        val again = receipt(compose(WEEKEND_PLAN, toolCallId = "call-2"))
        assertEquals(first.copy(warnings = listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING)), again)
        assertEquals(1, session.opLog.getOps(canvasId).size)
    }

    @Test
    fun theArtifactIdDerivesFromTheToolCallId() = runTest {
        conversationCanvas()
        val receipt = receipt(compose("""{"items":[{"kind":"NOTE","markdown":"Hello"}]}""", toolCallId = "toolu_01ABC"))
        assertEquals(CanvasComposeIds.derived("toolu_01ABC"), receipt.artifactId)
    }

    @Test
    fun anAgentThatMayNotWriteIsRefusedAsUnauthorized() = runTest {
        conversationCanvas()
        val request = """{"canvas_id":"${canvasId.value}","items":[{"kind":"NOTE","markdown":"x"}]}"""
        assertEquals(ComposeErrorCode.UNAUTHORIZED, refusal(compose(request, agent = "agent-2", conversation = "conv-2")).code)
        assertEquals(1L, store.get(canvasId)!!.revision)
        val error = assertIs<ExternalToolResult.Error>(compose(request, agent = null)).error
        assertTrue("authenticated agent identity" in error)
    }

    @Test
    fun theGuideAnswersTheWholeFormat() = runTest {
        val result = registry.invoke(CanvasToolContract.COMPOSE_GUIDE, JsonObject(emptyMap()), AGENT)
        assertEquals(CanvasComposeGuide.text, assertIs<ExternalToolResult.Success>(result).content)
    }

    private companion object {
        const val AGENT = HostCanvasComposeToolsTest.AGENT
        const val CONVERSATION = HostCanvasComposeToolsTest.CONVERSATION
        val WEEKEND_PLAN: String get() = HostCanvasComposeToolsTest.WEEKEND_PLAN
    }
}
