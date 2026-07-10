package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class DesktopAppServerChatAdapterTest {

    @Test
    fun createDefaultDesktopChatGateway_whenAppServerDisabled_returnsHttpGateway() = runBlocking {
        val config = LettaConfig(
            id = "test-config",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8080",
        )
        val appServerConfig = DesktopAppServerRuntimeConfig(enabled = false)

        val gateway = createDefaultDesktopChatGateway(
            config = config,
            appServerConfig = appServerConfig,
            appServerGatewayFactory = null, // No factory needed when disabled
        )

        assertIs<DesktopLettaHttpChatGateway>(gateway)
        gateway.close()
    }

    @Test
    fun createDefaultDesktopChatGateway_whenAppServerEnabledButNoFactory_throws() = runBlocking {
        val config = LettaConfig(
            id = "test-config",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8080",
        )
        val appServerConfig = DesktopAppServerRuntimeConfig(
            enabled = true,
            serverUrl = "ws://localhost:4500",
        )

        assertFailsWith<DesktopAppServerClientUnavailableException> {
            createDefaultDesktopChatGateway(
                config = config,
                appServerConfig = appServerConfig,
                appServerGatewayFactory = null, // No factory provided
            )
        }
    }

    @Test
    fun createDefaultDesktopChatGateway_whenAppServerEnabledWithFactory_returnsAppServerGateway() = runBlocking {
        val config = LettaConfig(
            id = "test-config",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8080",
        )
        val appServerConfig = DesktopAppServerRuntimeConfig(
            enabled = true,
            serverUrl = "ws://localhost:4500",
        )
        val factory = DesktopAppServerControllerGatewayFactory()

        val gateway = createDefaultDesktopChatGateway(
            config = config,
            appServerConfig = appServerConfig,
            appServerGatewayFactory = factory,
        )

        // The factory returns a hybrid gateway
        assertIs<DesktopHybridAppServerChatGateway>(gateway)
        gateway.close()
    }

    @Test
    fun desktopAppServerRuntimeConfig_fromProcess_readsSystemProperty() {
        try {
            System.setProperty(DesktopAppServerRuntimeConfig.ENABLED_PROPERTY, "true")
            System.setProperty(DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY, "ws://test:4500")

            val config = DesktopAppServerRuntimeConfig.fromProcess()

            assertEquals(true, config.enabled)
            assertEquals("ws://test:4500", config.serverUrl)
        } finally {
            System.clearProperty(DesktopAppServerRuntimeConfig.ENABLED_PROPERTY)
            System.clearProperty(DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY)
        }
    }

    @Test
    fun desktopAppServerRuntimeConfig_fromProcess_defaultsToDisabled() {
        // Ensure no environment variables or system properties are set
        val config = DesktopAppServerRuntimeConfig.fromProcess()
        assertEquals(false, config.enabled)
    }

    @Test
    fun defaultDesktopAppServerGatewayFactory_returnsFactory() {
        val factory = defaultDesktopAppServerGatewayFactory()
        assertNotNull(factory, "Default factory should be available when controller classes are present")
    }

    @Test
    fun desktopAppServerControllerGatewayFactory_requiresServerUrl() = runBlocking {
        val config = LettaConfig(
            id = "test-config",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8080",
        )
        val appServerConfig = DesktopAppServerRuntimeConfig(
            enabled = true,
            serverUrl = null, // Missing server URL
        )
        val factory = DesktopAppServerControllerGatewayFactory()

        assertFailsWith<IllegalArgumentException> {
            factory.create(config, appServerConfig)
        }
    }
}
