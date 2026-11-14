package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.TelemetryValue
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A slow conversation.list or message.list says whether its time went to the App Server or to this host. */
class AdminRpcSlowCallPhasesTest {
    @Test
    fun slowMessageListSeparatesAppServerWaitFromWrapperWork() = runTest {
        val client = object : AppServerClient by FakeNativeClient() {
            override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList):
                AppServerInboundFrame.ConversationMessagesListResponse {
                Thread.sleep(SLOW_MS)
                return AppServerInboundFrame.ConversationMessagesListResponse(
                    command.requestId, true, JsonArray(emptyList()),
                )
            }
        }
        val slow = slowCall("message.list", client, null) { put("conversation_id", "conv-1") }
        assertTrue(slow.millis("appServerMs") >= SLOW_MS, "the App Server wait is reported")
        assertTrue(slow.millis("wrapperMs") < SLOW_MS, "the rest of the call is reported as wrapper work")
        assertEquals(0L, slow.millis("discoveryMs"), "message.list runs no discovery")
    }

    @Test
    fun slowConversationListReportsDiscoveryApartFromItsListWait() = runTest {
        val root = createTempDirectory("slow-call-phases").toFile()
        try {
            val name = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:local-conv-1".toByteArray())
            File(root, "conversations/$name").apply { mkdirs() }.resolve("conversation.json")
                .writeText("""{"id":"local-conv-1","agent_id":"agent-1"}""")
            val client = object : AppServerClient by FakeNativeClient() {
                override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve):
                    AppServerInboundFrame.ConversationRetrieveResponse {
                    Thread.sleep(SLOW_MS)
                    val row = readValidTestConversation(File(root, "conversations/$name"))
                        ?.takeIf { (it["id"] as? JsonPrimitive)?.contentOrNull == command.conversationId }
                    return AppServerInboundFrame.ConversationRetrieveResponse(command.requestId, row != null, row)
                }
            }
            val slow = slowCall("conversation.list", client, LocalBackendAdminStore(root)) {}
            assertTrue(slow.millis("discoveryMs") >= SLOW_MS, "discovery's retrieve is reported as discovery")
            assertTrue(slow.millis("appServerMs") < SLOW_MS, "and is not counted again as the list's App Server wait")
            assertNotNull(slow.attrs["wrapperMs"])
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun slowCall(
        method: String,
        client: AppServerClient,
        store: LocalBackendAdminStore?,
        params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Telemetry.Event {
        val previousEnabled = Telemetry.enabled.get()
        val recorder = TelemetryRecorder()
        val recording = Telemetry.installRecorder(recorder)
        try {
            Telemetry.enabled.set(true)
            val router = AdminRpcRouter()
            ConversationAdminHandlers.register(
                router, NativeReadTiers(nativeClient = client, localBackendStore = store),
            )
            router.dispatch(AdminRpcInvocation("test", method, buildJsonObject(params)))
            return recorder.snapshot().single { it.tag == "AdminRpc" && it.name == "handler.slow" }
        } finally {
            recording.close()
            Telemetry.enabled.set(previousEnabled)
        }
    }

    private fun Telemetry.Event.millis(key: String): Long =
        assertNotNull(attrs[key] as? TelemetryValue.Numeric, "$key missing from $attrs").value.toLong()

    private companion object {
        const val SLOW_MS = 300L
    }
}
