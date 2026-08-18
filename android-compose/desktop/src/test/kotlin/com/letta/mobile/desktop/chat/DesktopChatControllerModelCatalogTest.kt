package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerModelCatalogTest {

    @Test
    fun modelCatalogLoadFailureEmitsADiagnosableTelemetryEventInsteadOfSilentlyDegrading() = runTest {
        val savedDelegate = com.letta.mobile.util.Telemetry.delegate
        com.letta.mobile.util.Telemetry.delegate = null
        val before = com.letta.mobile.util.Telemetry.events.value.size
        val gateway = FailingModelCatalogGateway()
        val controller = testController(gateway)

        try {
            controller.start()
            runCurrent()

            // The dropdown itself degrades silently (Result.failure has no UI sink) —
            // that's the exact "looks like no models" symptom this test guards
            // against being un-diagnosable. The fix is the telemetry event, not a
            // change in _availableModels' empty-on-failure behavior.
            assertTrue(controller.availableModels.value.isEmpty())
            // Telemetry.emit prepends (newest first), so events added by this test
            // sit at the FRONT of the list, not appended at the end.
            val after = com.letta.mobile.util.Telemetry.events.value
            val newEvents = after.take(after.size - before)
            val modelCatalogEvent = newEvents.singleOrNull { it.name == "modelCatalog.loadFailed" }
            assertNotNull(modelCatalogEvent, "expected a modelCatalog.loadFailed telemetry event")
            assertEquals("IllegalStateException", modelCatalogEvent.attrs["exceptionClass"])
        } finally {
            controller.close()
            com.letta.mobile.util.Telemetry.delegate = savedDelegate
        }
    }

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
        )
}

/**
 * Implements [ChatGatewayExtras] (unlike [FakeDesktopChatGateway]) so
 * [DesktopChatController.start] actually drives the model-catalog load path,
 * with [listLlmModels] always throwing — mirrors an App Server request that
 * fails (timeout, decode failure, or anything else) rather than a backend
 * that genuinely has zero models.
 */
private class FailingModelCatalogGateway : FakeDesktopChatGateway(), ChatGatewayExtras {
    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        error("not used by this test")

    override suspend fun createAgent(params: AgentCreateParams): Agent = error("not used by this test")

    override suspend fun listLlmModels(): List<LlmModel> = error("model catalog fetch failed")

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        error("not used by this test")

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        error("not used by this test")
}
