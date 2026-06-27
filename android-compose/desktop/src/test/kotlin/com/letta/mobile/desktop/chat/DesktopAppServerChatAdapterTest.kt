package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class DesktopAppServerChatAdapterTest {
    @Test
    fun defaultGatewayUsesExistingHttpSseChatWhenAppServerDisabled() {
        val gateway = createDefaultDesktopChatGateway(
            config = desktopConfig(),
            appServerConfig = DesktopAppServerRuntimeConfig(enabled = false),
            appServerGatewayFactory = DesktopAppServerChatGatewayFactory { _, _ ->
                error("App Server factory should not be invoked")
            },
        )

        assertIs<DesktopLettaHttpChatGateway>(gateway)
        gateway.close()
    }

    @Test
    fun appServerGatewayRequiresSharedClientFactoryWhenEnabled() {
        assertFailsWith<DesktopAppServerClientUnavailableException> {
            createDefaultDesktopChatGateway(
                config = desktopConfig(),
                appServerConfig = DesktopAppServerRuntimeConfig(
                    enabled = true,
                    serverUrl = "ws://localhost:3131",
                ),
                appServerGatewayFactory = null,
            )
        }
    }

    @Test
    fun appServerGatewayFactoryReceivesDesktopAndRuntimeConfigWhenEnabled() {
        val config = desktopConfig()
        val appServerConfig = DesktopAppServerRuntimeConfig(
            enabled = true,
            serverUrl = "ws://localhost:3131",
        )
        val expectedGateway = EmptyDesktopChatGateway()
        var capturedConfig: LettaConfig? = null
        var capturedAppServerConfig: DesktopAppServerRuntimeConfig? = null

        val gateway = createDefaultDesktopChatGateway(
            config = config,
            appServerConfig = appServerConfig,
            appServerGatewayFactory = DesktopAppServerChatGatewayFactory { lettaConfig, runtimeConfig ->
                capturedConfig = lettaConfig
                capturedAppServerConfig = runtimeConfig
                expectedGateway
            },
        )

        assertSame(expectedGateway, gateway)
        assertEquals(config, capturedConfig)
        assertEquals(appServerConfig, capturedAppServerConfig)
    }

    private fun desktopConfig(): LettaConfig =
        LettaConfig(
            id = "desktop-local",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )
}

private class EmptyDesktopChatGateway : DesktopChatGateway {
    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> = emptyList()

    override suspend fun getConversation(conversationId: String): Conversation =
        error("not used")

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = emptyFlow()

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        emptyFlow()

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = emptyList()
}
