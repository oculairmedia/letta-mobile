package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertIs

/**
 * The three places an agent's canvas_* calls run (letta-mobile-s416w.5), behind one face so a plugin
 * element test runs on each: the Iroh host (the relay's log), an app with the board open (its live
 * session) and an app with the board closed (its store). Every call names the conversation's canvas.
 */
internal sealed class PluginToolHost(val name: String) {
    abstract val registry: ExternalToolRegistry

    /** The ops published so far, in order; null where nothing keeps them (a closed board). */
    abstract suspend fun logged(): List<CanvasOp>?

    suspend fun call(tool: String, input: JsonObject, agent: String = AGENT): ExternalToolResult =
        registry.invoke(tool, JsonObject(input + ("canvas_id" to Json.parseToJsonElement("\"$CANVAS\""))), ExternalToolCaller(agent, CONVERSATION, TOOL_CALL))

    suspend fun applyOps(vararg ops: String, dryRun: Boolean = false, agent: String = AGENT): ExternalToolResult =
        call(CanvasToolContract.APPLY_OPS, opsInput(*ops, dryRun = dryRun), agent)

    suspend fun scene(): CanvasGetSceneResult =
        json.decodeFromString(CanvasGetSceneResult.serializer(), call(CanvasToolContract.GET_SCENE, JsonObject(emptyMap())).content())

    override fun toString(): String = name

    class Iroh : PluginToolHost("the Iroh host") {
        val store = InMemoryCanvasRelayStore()
        val relay = CanvasRelayHost(store, hostId = { "host-1" })
        override val registry = ExternalToolRegistry.hostTools(HostCanvasTools.all(HostCanvasBackend(relay, store, InMemoryHostCanvasDirectory())))

        override suspend fun logged(): List<CanvasOp> = store.readAfter(TOPIC, 0L).map { it.op }
    }

    class OpenApp private constructor(private val session: CanvasSession, store: CanvasDocumentStore, sessions: CanvasSessionRegistry) :
        PluginToolHost("an app with the board open") {
        override val registry = ExternalToolRegistry.hostTools(CanvasExternalTools.all(store, sessions))

        override suspend fun logged(): List<CanvasOp> = session.opLog.getOps(CanvasId(CANVAS))

        companion object {
            suspend fun create(): OpenApp {
                val store = InMemoryCanvasDocumentStore()
                val sessions = CanvasSessionRegistry()
                val session = CanvasSession.create(store, CanvasCreateOptions(conversationId = CONVERSATION, agentId = AGENT, canvasId = CanvasId(CANVAS)))
                sessions.register(session)
                return OpenApp(session, store, sessions)
            }
        }
    }

    class ClosedApp private constructor(store: CanvasDocumentStore) : PluginToolHost("an app with the board closed") {
        override val registry = ExternalToolRegistry.hostTools(CanvasExternalTools.all(store, CanvasSessionRegistry()))

        override suspend fun logged(): List<CanvasOp>? = null

        companion object {
            suspend fun create(): ClosedApp {
                val store = InMemoryCanvasDocumentStore()
                store.upsert(
                    CanvasDocument(
                        id = CanvasId(CANVAS), agentId = AGENT, conversationId = CONVERSATION, title = "Conversation canvas",
                        revision = 0L, sceneJson = "", acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(AGENT)),
                        updatedAtEpochMs = 0L,
                    ),
                )
                return ClosedApp(store)
            }
        }
    }

    companion object {
        const val AGENT = "agent-1"
        const val INTRUDER = "agent-2"
        const val CONVERSATION = "conv-plugin"
        const val TOOL_CALL = "toolu_plugin_1"
        val CANVAS: String = CanvasId.forConversation(CONVERSATION).value
        val TOPIC: String = CanvasRelayProtocol.conversationTopic(CONVERSATION)

        val json = Json { ignoreUnknownKeys = true }

        /** One of each, fresh. */
        suspend fun all(): List<PluginToolHost> = listOf(Iroh(), OpenApp.create(), ClosedApp.create())

        fun opsInput(vararg ops: String, dryRun: Boolean = false): JsonObject = JsonObject(
            buildMap {
                put("ops", JsonArray(ops.map { Json.parseToJsonElement(it) }))
                if (dryRun) put(CanvasDryRun.PARAM, Json.parseToJsonElement("true"))
            },
        )

        fun ExternalToolResult.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

        fun ExternalToolResult.error(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error

        /** [sceneJson] with every op id dropped: the ids each host mints are the one thing that differs. */
        fun withoutOpIds(sceneJson: String): JsonElement = if (sceneJson.isBlank()) JsonObject(emptyMap()) else strip(Json.parseToJsonElement(sceneJson))

        private fun strip(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.filterKeys { !it.lowercase().contains("opid") }.mapValues { strip(it.value) })
            is JsonArray -> JsonArray(element.map(::strip))
            else -> element
        }

        // ---- Ops as a model writes them: no opId, actorId or lamport ----

        const val SNAPSHOT_REF = "sha256:a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"

        const val PLACE = """{"type":"set_plugin_element","elementId":"pe-1","elementType":"ext:letta.example/widget","v":1,""" +
            """"frame":{"x":40,"y":60,"width":320,"height":240},"ref":"job:7f3a","props":{"status":"queued","progress":0},""" +
            """"snapshot":{"assetRef":"$SNAPSHOT_REF","mediaType":"image/png","width":1024,"height":1024,"rev":1},""" +
            """"fallback":{"title":"Example widget","subtitle":"queued","openUrl":"https://example.test/widgets/1"}}"""

        /** A state update with props as a JSON string, as a model writes elementJson. */
        const val PROGRESS = """{"type":"set_plugin_element","elementId":"pe-1","props":"{\"status\":\"running\",\"progress\":0.5}"}"""

        const val MOVE = """{"type":"set_plugin_element","elementId":"pe-1","frame":{"x":400,"y":640,"width":320,"height":240},"owner":"user"}"""

        const val REMOVE = """{"type":"remove_plugin_element","elementId":"pe-1"}"""

        /** A first write with no fallback and no snapshot: the envelope is incomplete. */
        const val INCOMPLETE = """{"type":"set_plugin_element","elementId":"pe-2","elementType":"ext:letta.example/widget","v":1}"""

        /** A type outside the ext: namespace. */
        const val BAD_TYPE = """{"type":"set_plugin_element","elementId":"pe-3","elementType":"widget","v":1,"fallback":{"title":"x","openUrl":"https://example.test"}}"""

        /** A drawn element beside the plugin ops, in the same batch. */
        val shape: String get() = """{"type":"add_element","elementId":"box-1","elementJson":${CanvasSceneSchema.shape.example}}"""
    }
}
