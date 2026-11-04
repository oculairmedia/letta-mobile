package com.letta.mobile.data.transport

import kotlin.test.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class InboxArrivalContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun appServerClientChannelCarriesActivitySeparatelyFromConversationInvalidation() = kotlinx.coroutines.test.runTest {
        val source = kotlinx.coroutines.flow.MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
        val client = java.lang.reflect.Proxy.newProxyInstance(
            com.letta.mobile.data.transport.appserver.AppServerClient::class.java.classLoader,
            arrayOf(com.letta.mobile.data.transport.appserver.AppServerClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEvents" -> source
                "isConnected", "getIsConnected" -> kotlinx.coroutines.flow.flowOf(true)
                else -> error("activity must not call App Server: ${method.name}")
            }
        } as com.letta.mobile.data.transport.appserver.AppServerClient
        val channel = com.letta.mobile.data.transport.api.AppServerClientChannel(client, backgroundScope)
        val received = mutableListOf<ServerFrame>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            channel.events.collect { received += it }
        }
        runCurrent()
        val wire = checkNotNull(javaClass.getResource("/inbox-arrival/conversation-activity.json")).readText()
        source.emit(com.letta.mobile.data.transport.appserver.AppServerProtocol.decodeFrame(wire,
            com.letta.mobile.data.transport.appserver.AppServerChannel.Stream))
        runCurrent()
        assertEquals(1, received.size, "the client channel must publish the activity frame")
        assertIs<ServerFrame.ConversationActivity>(received.single())
    }

    @Test
    fun persistedActivityUsesItsOwnFrameType() {
        val fixture = checkNotNull(javaClass.getResource("/inbox-arrival/conversation-activity.json")).readText()
        val decoded = json.decodeFromString(ServerFrameSerializer, fixture)
        val activity = assertIs<ServerFrame.ConversationActivity>(decoded)
        val original = json.parseToJsonElement(fixture).jsonObject
        val encoded = json.encodeToJsonElement(ServerFrame.ConversationActivity.serializer(), activity).jsonObject
        listOf("agent_id", "conversation_id", "milestone", "source_message_id", "client_message_id", "at").forEach {
            assertEquals(original[it], encoded[it], it)
        }
        assertEquals(null, WsFrameMapper.toLettaMessage(activity))
        val future = json.decodeFromString(ServerFrameSerializer, fixture.replace("persisted", "future-milestone"))
        assertEquals(null, WsFrameMapper.toLettaMessage(future))
    }

    @Test
    fun committedUserIdentityIsReadFromTheTranscript() {
        val root = java.nio.file.Files.createTempDirectory("arrival-transcript").toFile()
        try {
            val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:conv-inbox".toByteArray())
            val dir = java.io.File(root, "conversations/$key").apply { mkdirs() }
            java.io.File(dir, "conversation.json").writeText("""{"id":"conv-inbox","agent_id":"agent-recipient","tags":["lane:inbox"]}""")
            java.io.File(dir, "messages.jsonl").writeText(
                """{"id":"source-1","role":"user","otid":"client-stable","content":[{"type":"text","text":"wake"}]}""" + "\n",
            )
            val store = com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore(root)
            val messages = checkNotNull(store.listMessagesProjected("conv-inbox", "agent-recipient",
                com.letta.mobile.data.controller.node.iroh.MessagePage(64, null, null, "desc")))
            assertEquals(kotlinx.serialization.json.JsonPrimitive("client-stable"), messages.single().jsonObject["otid"])
        } finally { root.deleteRecursively() }
    }

    @Test
    fun messageIdentitySurvivesTheRealFrameDecoder() {
        val fixture = checkNotNull(javaClass.getResource("/inbox-arrival/conversation-update-message-id.json")).readText()
        val original = json.parseToJsonElement(fixture).jsonObject
        val decoded = assertIs<ServerFrame.ConversationUpdated>(json.decodeFromString(ServerFrameSerializer, fixture))
        val encoded = json.encodeToJsonElement(ServerFrame.ConversationUpdated.serializer(), decoded).jsonObject

        assertEquals(original["message_id"], encoded["message_id"], "arrival identity must survive decoding")
        assertEquals(original["conversation_id"], encoded["conversation_id"])
        assertEquals(original["agent_id"], encoded["agent_id"])
        assertEquals(original["reason"], encoded["reason"])
    }
}
