package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.CanvasComposeGuide
import com.letta.mobile.data.canvas.compose.CanvasComposeIds
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeErrorCode
import com.letta.mobile.data.canvas.compose.ComposeProblemCode
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeReceiptItem
import com.letta.mobile.data.canvas.compose.ComposeRefusal
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * canvas_compose on the Iroh host (letta-mobile-bglj6.12): the fixture request published through
 * the relay's log as one batch, dry runs, structured refusals, retries and the caller checks.
 */
class HostCanvasComposeToolsTest {
    private val json = Json { ignoreUnknownKeys = true }

    internal class Host(val store: CanvasRelayStore = InMemoryCanvasRelayStore()) {
        val backend = HostCanvasBackend(CanvasRelayHost(store, hostId = { "host-1" }), store, InMemoryHostCanvasDirectory())
        val registry = ExternalToolRegistry.hostTools(HostCanvasTools.all(backend))

        suspend fun compose(
            request: String,
            agent: String? = AGENT,
            conversation: String? = CONVERSATION,
            toolCallId: String? = "call-1",
        ): ExternalToolResult = registry.invoke(
            CanvasToolContract.COMPOSE,
            Json.parseToJsonElement(request).jsonObject,
            agentId = agent,
            conversationId = conversation,
            toolCallId = toolCallId,
        )

        suspend fun logged(): List<CanvasRelayEntry> = store.readAfter(TOPIC, 0L)

        suspend fun scene(): CanvasGetSceneResult = Json { ignoreUnknownKeys = true }.decodeFromString(
            assertIs<ExternalToolResult.Success>(
                registry.invoke(CanvasToolContract.GET_SCENE, JsonObject(emptyMap()), agentId = AGENT, conversationId = CONVERSATION),
            ).content,
        )
    }

    @Test
    fun theHostAdvertisesComposeAndItsGuide() {
        val sent = Host().registry.advertisedToolsCommandGroups()!!.flatMap { it.tools }
        listOf(CanvasToolContract.compose, CanvasToolContract.composeGuide).forEach { definition ->
            val tool = sent.single { it.name == definition.name }
            assertEquals(definition.description, tool.description)
            assertEquals(definition.inputSchema, tool.parameters)
        }
    }

    @Test
    fun theFixtureIsPublishedWholeAsOneLoggedBatch() = runTest {
        val host = Host()
        val receipt = receipt(host.compose(WEEKEND_PLAN))

        assertEquals(ComposeStatus.PUBLISHED, receipt.status)
        assertEquals("weekend-plan", receipt.artifactId)
        assertEquals(CanvasId.forConversation(CONVERSATION).value, receipt.canvasId)
        val batch = assertIs<CanvasOp.BatchOp>(host.logged().single().op, "the artifact is one entry in the log")
        assertEquals(AGENT, batch.actorId)
        assertTrue(batch.ops.isNotEmpty())
        assertTrue(batch.ops.all { it.actorId == AGENT && it.opId.isNotBlank() })
        assertEquals(batch.ops.size, batch.ops.map { it.opId }.toSet().size, "every op keeps an id of its own")
        assertEquals((1L..batch.ops.size).toList(), batch.ops.map { it.lamport })
        assertEquals(batch.ops.last().lamport, batch.lamport)

        val scene = host.scene()
        assertEquals(scene.revision, receipt.revision, "the receipt's revision is the one get_scene answers next")
        val documents = CanvasOpProjector.documentsOf(scene.sceneJson)
        val composed = documents.filter { it.compose != null }
        assertTrue(composed.isNotEmpty())
        composed.forEach { document ->
            assertEquals(CanvasGeometryOwner.AUTO, document.owner)
            assertEquals("weekend-plan", document.compose!!.artifactId)
            assertEquals(CanvasComposeContract.CATALOG, document.compose!!.catalog)
        }
        receipt.items.flatMap { listOf(it) + it.children.orEmpty() }.forEach { item: ComposeReceiptItem ->
            val id = item.boardId(receipt.artifactId)
            assertTrue("\"$id\"" in scene.sceneJson, "$id is not on the board")
        }
    }

    @Test
    fun aDryRunPublishesNothing() = runTest {
        listOf(JsonPrimitive(true), JsonPrimitive("true")).forEach { flag ->
            val host = Host()
            val request = Json.parseToJsonElement(WEEKEND_PLAN).jsonObject.let { JsonObject(it + ("dry_run" to flag)) }
            val receipt = receipt(host.compose(request.toString()))
            assertEquals(ComposeStatus.DRY_RUN, receipt.status, "dry_run $flag")
            assertNull(receipt.revision)
            assertTrue(host.logged().isEmpty())
            assertEquals(0L, host.scene().revision)
        }
    }

    @Test
    fun aRefusalIsStructuredJsonAndPublishesNothing() = runTest {
        val host = Host()
        val refusal = refusal(host.compose("""{"items":[{"kind":"POEM","text":"x"}]}"""))
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertTrue(refusal.problems.any { it.path == "/items/0/kind" && it.code == ComposeProblemCode.UNKNOWN_KIND.name }, "$refusal")
        assertTrue(host.logged().isEmpty())
        assertEquals(0L, host.scene().revision)
    }

    @Test
    fun aRetryWithTheSameArtifactIdAnswersTheSameReceiptAndWritesNothing() = runTest {
        val host = Host()
        val first = receipt(host.compose(WEEKEND_PLAN, toolCallId = "call-1"))
        val again = receipt(host.compose(WEEKEND_PLAN, toolCallId = "call-2"))
        assertEquals(first.copy(warnings = listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING)), again)
        assertEquals(1, host.logged().size)
    }

    @Test
    fun theArtifactIdDerivesFromTheToolCallId() = runTest {
        val host = Host()
        val request = """{"items":[{"kind":"NOTE","markdown":"Hello"}]}"""
        val first = receipt(host.compose(request, toolCallId = "toolu_01ABC"))
        assertEquals(CanvasComposeIds.derived("toolu_01ABC"), first.artifactId)
        // The runtime retrying the same call lands on the same artifact.
        val retried = receipt(host.compose(request, toolCallId = "toolu_01ABC"))
        assertEquals(first.artifactId, retried.artifactId)
        assertEquals(1, host.logged().size)
        // Another call is another artifact.
        val other = receipt(host.compose(request, toolCallId = "toolu_02DEF"))
        assertEquals(CanvasComposeIds.derived("toolu_02DEF"), other.artifactId)
        assertEquals(2, host.logged().size)
    }

    @Test
    fun anotherAgentsCanvasIsRefusedAsUnauthorized() = runTest {
        val host = Host()
        receipt(host.compose(WEEKEND_PLAN))
        val request = """{"canvas_id":"${CanvasId.forConversation(CONVERSATION).value}","items":[{"kind":"NOTE","markdown":"mine now"}]}"""
        val refusal = refusal(host.compose(request, agent = "agent-2", conversation = "conv-2"))
        assertEquals(ComposeErrorCode.UNAUTHORIZED, refusal.code)
        assertEquals(1, host.logged().size)
    }

    @Test
    fun aMissingCanvasIsRefusedAsNotFound() = runTest {
        val host = Host()
        val unknown = refusal(host.compose("""{"canvas_id":"canvas-nope","items":[{"kind":"NOTE","markdown":"x"}]}"""))
        assertEquals(ComposeErrorCode.CANVAS_NOT_FOUND, unknown.code)
        val noConversation = refusal(host.compose("""{"items":[{"kind":"NOTE","markdown":"x"}]}""", conversation = null))
        assertEquals(ComposeErrorCode.CANVAS_NOT_FOUND, noConversation.code)
    }

    @Test
    fun aCallWithoutAnAgentIsRefusedLikeEveryCanvasTool() = runTest {
        val error = assertIs<ExternalToolResult.Error>(Host().compose(WEEKEND_PLAN, agent = null)).error
        assertTrue("authenticated agent identity" in error, error)
    }

    @Test
    fun theGuideAnswersTheWholeFormat() = runTest {
        val result = Host().registry.invoke(CanvasToolContract.COMPOSE_GUIDE, JsonObject(emptyMap()), agentId = AGENT, conversationId = CONVERSATION)
        assertEquals(CanvasComposeGuide.text, assertIs<ExternalToolResult.Success>(result).content)
    }

    internal companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        val TOPIC = CanvasRelayProtocol.conversationTopic(CONVERSATION)
        val WEEKEND_PLAN: String get() = CanvasComposeGuide.EXAMPLE_REQUEST

        fun receipt(result: ExternalToolResult): ComposeReceipt = CanvasComposeContract.json.decodeFromString(
            ComposeReceipt.serializer(),
            assertIs<ExternalToolResult.Success>(result, "expected a receipt, got $result").content,
        )

        fun refusal(result: ExternalToolResult): ComposeRefusal = CanvasComposeContract.json.decodeFromString(
            ComposeRefusal.serializer(),
            assertIs<ExternalToolResult.Error>(result, "expected a refusal, got $result").error,
        )
    }
}
