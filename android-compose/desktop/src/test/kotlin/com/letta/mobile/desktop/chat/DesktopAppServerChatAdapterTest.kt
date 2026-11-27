package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * The desktop dials an App Server directly only for a developer: an override address, or a ws:// Remote host when the
 * developer setting is on. The bundled runtime is never dialled here; the shell reaches it through the desktop's node.
 */
class DesktopAppServerChatAdapterTest {

    @Test
    fun remoteHostDialsItsOwnAppServerAddressWhenTheDeveloperSettingIsOn() = runBlocking {
        val received = dialRecording(LettaConfig("remote", LettaConfig.Mode.SELF_HOSTED, " ws://remote:4500 "), directAllowed = true)

        assertEquals("ws://remote:4500", received.second?.serverUrl)
        assertIs<DesktopAppServerChatGateway>(received.first).close()
    }

    @Test
    fun aWsRemoteHostIsRefusedWithoutTheDeveloperSetting() = runBlocking {
        val refused = assertFailsWith<IllegalStateException> {
            dialRecording(LettaConfig("remote", LettaConfig.Mode.SELF_HOSTED, "ws://remote:4500"), directAllowed = false)
        }
        assertTrue(refused.message.orEmpty().contains(DesktopAppServerRuntimeConfig.DIRECT_APP_SERVER_PROPERTY), "${refused.message}")
    }

    @Test
    fun remoteHostWithoutAnAddressFailsInsteadOfFallingBackToAnotherServer() = runBlocking {
        val config = LettaConfig("remote", LettaConfig.Mode.SELF_HOSTED, "")

        assertFailsWith<IllegalArgumentException> {
            createDefaultDesktopChatGateway(
                config = config,
                appServerConfig = DesktopAppServerRuntimeConfig(),
                appServerGatewayFactory = DesktopAppServerChatGatewayBuilder(),
                directAppServerAllowed = true,
            )
        }
        Unit
    }

    @Test
    fun theBundledRuntimeIsNeverDialledDirectly() = runBlocking {
        // A LOCAL config, even one still carrying a stale iroh:// address, is served by the desktop's own node.
        for (serverUrl in listOf("", "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501")) {
            assertFailsWith<IllegalStateException>(serverUrl) {
                dialRecording(LettaConfig("local", LettaConfig.Mode.LOCAL, serverUrl), directAllowed = true)
            }
        }
    }

    @Test
    fun explicitOverrideWinsForAnyMode() = runBlocking {
        val (gateway, received) = dialRecording(
            config = LettaConfig("local", LettaConfig.Mode.LOCAL, ""),
            appServerConfig = DesktopAppServerRuntimeConfig(serverUrl = "ws://override:4500"),
            directAllowed = false,
        )

        assertEquals("ws://override:4500", received?.serverUrl)
        assertIs<DesktopAppServerChatGateway>(gateway).close()
    }

    @Test
    fun desktopAppServerRuntimeConfig_fromProcess_readsSystemProperty() {
        try {
            System.setProperty(DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY, "ws://test:4500")

            assertEquals("ws://test:4500", DesktopAppServerRuntimeConfig.fromProcess().serverUrl)
        } finally {
            System.clearProperty(DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY)
        }
    }

    @Test
    fun desktopAppServerRuntimeConfig_fromProcess_hasNoOverrideByDefault() {
        assertNull(DesktopAppServerRuntimeConfig.fromProcess().serverUrl)
    }

    @Test
    fun theDirectAppServerDeveloperSettingIsOffUnlessSet() {
        val property = DesktopAppServerRuntimeConfig.DIRECT_APP_SERVER_PROPERTY
        val previous = System.getProperty(property)
        try {
            // The property wins over the environment, so the default is checked whatever the developer's shell sets.
            System.setProperty(property, "false")
            assertFalse(DesktopAppServerRuntimeConfig.directAppServerAllowed())
            System.setProperty(property, "true")
            assertTrue(DesktopAppServerRuntimeConfig.directAppServerAllowed())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun desktopAppServerControllerGatewayFactory_requiresServerUrl() = runBlocking {
        val config = LettaConfig(id = "test-config", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "")

        assertFailsWith<IllegalArgumentException> {
            DesktopAppServerChatGatewayBuilder().create(config, DesktopAppServerRuntimeConfig(serverUrl = null))
        }
        Unit
    }

    private fun recordingFactory(onCreate: (DesktopAppServerRuntimeConfig) -> Unit) =
        DesktopAppServerChatGatewayFactory { _, appServer ->
            onCreate(appServer)
            DesktopAppServerChatGateway(turnEngine = IdleTurnEngine, client = IdleAppServerClient)
        }

    private object IdleTurnEngine : TurnEngine {
        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()
    }

    private object IdleAppServerClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            unused()

        override suspend fun input(command: AppServerCommand.Input): Unit = unused()

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = unused()

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            unused()

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            unused()

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unused()

        private fun unused(): Nothing = error("not used by the adapter tests")
    }

    /** Builds the default gateway, returning it and the config the factory received. */
    private suspend fun dialRecording(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig(),
        directAllowed: Boolean,
    ): Pair<DesktopChatGateway, DesktopAppServerRuntimeConfig?> {
        var received: DesktopAppServerRuntimeConfig? = null
        val gateway = createDefaultDesktopChatGateway(config, appServerConfig, recordingFactory { received = it }, directAllowed)
        return gateway to received
    }
}
