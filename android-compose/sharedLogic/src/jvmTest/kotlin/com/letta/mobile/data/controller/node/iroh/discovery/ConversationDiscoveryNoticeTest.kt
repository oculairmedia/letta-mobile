package com.letta.mobile.data.controller.node.iroh.discovery

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ConversationAdminHandlers
import com.letta.mobile.data.controller.node.iroh.ConversationChangeNotifier
import com.letta.mobile.data.controller.node.iroh.FakeNativeClient
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.controller.node.iroh.NativeReadTiers
import com.letta.mobile.data.controller.node.iroh.readValidTestConversation
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDiscoveryNoticeTest {
    @Test
    fun successfulExternalPrimeBroadcastsCreatedAndInvalidRecordDoesNot() = runTest {
        val root = createTempDirectory("discovery-notice").toFile()
        try {
            fun record(id: String, content: String) {
                val name = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
                File(root, "conversations/$name").apply { mkdirs() }.resolve("conversation.json").writeText(content)
            }
            val conversations = File(root, "conversations").apply { mkdirs() }
            val client = object : AppServerClient by FakeNativeClient() {
                private val loaded = mutableListOf<kotlinx.serialization.json.JsonObject>()

                override suspend fun conversationList(command: AppServerCommand.ConversationList) =
                    AppServerInboundFrame.ConversationListResponse(command.requestId, true, JsonArray(loaded.toList()))

                override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve):
                    AppServerInboundFrame.ConversationRetrieveResponse {
                    val name = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("conversation:${command.conversationId}".toByteArray())
                    val row = readValidTestConversation(File(root, "conversations/$name"))
                        ?.takeIf { (it["id"] as? JsonPrimitive)?.contentOrNull == command.conversationId }
                    if (row != null) loaded += row
                    return AppServerInboundFrame.ConversationRetrieveResponse(command.requestId, row != null, row)
                }
            }
            val frames = mutableListOf<String>()
            val notifier = ConversationChangeNotifier(backgroundScope, windowMs = 10).also { n -> n.attach { frames += it } }
            val router = AdminRpcRouter().also {
                ConversationAdminHandlers.register(it, NativeReadTiers(
                    nativeClient = client, localBackendStore = LocalBackendAdminStore(root), conversationChanges = notifier,
                ))
            }
            router.dispatch(AdminRpcInvocation("test", "conversation.list", null))
            // Created by another process after the first scan, so both are news.
            record("external-good", """{"id":"external-good","agent_id":"agent-1"}""")
            record("external-invalid", """{"id":"external-invalid"}""")
            check(conversations.setLastModified(conversations.lastModified() + 10_000))
            Thread.sleep(ConversationDiscovery.MIN_INTERVAL_MS + 100)
            router.dispatch(AdminRpcInvocation("test", "conversation.list", null))
            advanceTimeBy(11)
            runCurrent()
            assertEquals(1, frames.size, "only the successfully primed conversation broadcasts")
            val frame = assertIs<ServerFrame.ConversationUpdated>(
                Json { ignoreUnknownKeys = true }.decodeFromString(ServerFrameSerializer, frames.single()),
            )
            assertEquals("external-good", frame.conversationId)
            assertEquals("created", frame.reason)
            assertNull(frame.agentId)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * A wrapper restart finds every conversation already on disk: none of them is news, so priming
     * them must not tell clients to refetch. Only a conversation that appears afterwards is announced.
     */
    @Test
    fun restartWithConversationsOnDiskAnnouncesOnlyALaterOne() = runTest {
        val root = createTempDirectory("discovery-restart").toFile()
        try {
            val onDisk = (1..3).map { "on-disk-$it" }
            onDisk.forEach { record(root, it, """{"id":"$it","agent_id":"agent-1"}""") }
            val frames = mutableListOf<String>()
            val notifier = ConversationChangeNotifier(backgroundScope, windowMs = 10)
            notifier.attach { frames += it }
            val client = ScanOnceClient(root)
            val router = AdminRpcRouter().also {
                val tiers = NativeReadTiers(
                    nativeClient = client,
                    localBackendStore = LocalBackendAdminStore(root),
                    conversationChanges = notifier,
                )
                ConversationAdminHandlers.register(it, tiers)
            }
            router.dispatch(AdminRpcInvocation("test", "conversation.list", null))
            advanceTimeBy(11)
            runCurrent()
            assertEquals(onDisk, client.retrieved, "the baseline is still primed")
            assertEquals(emptyList(), frames, "conversations on disk at the first scan are not announced")

            record(root, "external-later", """{"id":"external-later","agent_id":"agent-1"}""")
            val conversations = File(root, "conversations")
            check(conversations.setLastModified(conversations.lastModified() + 10_000))
            // The discovery pass interval runs on the wall clock.
            Thread.sleep(ConversationDiscovery.MIN_INTERVAL_MS + 100)
            router.dispatch(AdminRpcInvocation("test", "conversation.list", null))
            advanceTimeBy(11)
            runCurrent()
            val frame = assertIs<ServerFrame.ConversationUpdated>(
                Json { ignoreUnknownKeys = true }.decodeFromString(ServerFrameSerializer, frames.single()),
            )
            assertEquals("external-later", frame.conversationId)
            assertEquals("created", frame.reason)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun record(root: File, id: String, content: String) {
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
        File(root, "conversations/$name").apply { mkdirs() }.resolve("conversation.json").writeText(content)
    }

    /**
     * letta.js 0.34.39: `listConversations` scans the directory once (`loadConversationRecordsFromStorage`),
     * and strict `retrieveConversation` resolves only a valid record with that id.
     */
    private class ScanOnceClient(private val root: File) : AppServerClient by FakeNativeClient() {
        private val loaded = linkedMapOf<String, kotlinx.serialization.json.JsonObject>()
        private var scanned = false
        val retrieved = mutableListOf<String>()

        override suspend fun conversationList(command: AppServerCommand.ConversationList):
            AppServerInboundFrame.ConversationListResponse {
            if (!scanned) {
                scanned = true
                File(root, "conversations").listFiles().orEmpty().forEach { dir ->
                    readValidTestConversation(dir)?.let { loaded[(it["id"] as JsonPrimitive).content] = it }
                }
            }
            val rows = JsonArray(loaded.values.toList())
            return AppServerInboundFrame.ConversationListResponse(command.requestId, true, rows)
        }

        override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve):
            AppServerInboundFrame.ConversationRetrieveResponse {
            retrieved += command.conversationId
            val name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("conversation:${command.conversationId}".toByteArray())
            val row = readValidTestConversation(File(root, "conversations/$name"))
                ?.takeIf { (it["id"] as? JsonPrimitive)?.contentOrNull == command.conversationId }
            if (row != null) loaded[command.conversationId] = row
            return AppServerInboundFrame.ConversationRetrieveResponse(command.requestId, row != null, row)
        }
    }
}
