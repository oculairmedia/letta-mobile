package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLifecycle
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLease
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
            localRuntime = FakeLocalRuntime(),
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
                localRuntime = FakeLocalRuntime(),
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
        val expected = DesktopLettaHttpChatGateway(config)
        val factory = DesktopAppServerChatGatewayFactory { _, _ -> expected }

        val gateway = createDefaultDesktopChatGateway(
            config = config,
            appServerConfig = appServerConfig,
            appServerGatewayFactory = factory,
            localRuntime = FakeLocalRuntime(),
        )

        assertEquals(expected, gateway)
        assertIs<DesktopLettaHttpChatGateway>(gateway).close()
    }

    @Test
    fun localModeStartsBundledRuntimeAndPassesItsUrlToFactory() = runBlocking {
        val runtime = FakeLocalRuntime(url = "ws://127.0.0.1:43123")
        var received: DesktopAppServerRuntimeConfig? = null
        val gateway = createDefaultDesktopChatGateway(
            config = LettaConfig("local", LettaConfig.Mode.LOCAL, "local://bundled"),
            appServerConfig = DesktopAppServerRuntimeConfig(enabled = false),
            appServerGatewayFactory = DesktopAppServerChatGatewayFactory { config, appServer ->
                received = appServer
                DesktopLettaHttpChatGateway(config.copy(serverUrl = "http://unused.invalid"))
            },
            localRuntime = runtime,
        )

        assertEquals(1, runtime.acquireCount)
        assertEquals(0, runtime.closeCount)
        assertEquals("ws://127.0.0.1:43123", received?.serverUrl)
        assertIs<AutoCloseable>(gateway).close()
        assertEquals(1, runtime.closeCount)
    }

    @Test
    fun remoteModeDoesNotAcquireBundledRuntimeWhenExplicitAppServerIsEnabled() = runBlocking {
        val runtime = FakeLocalRuntime()
        val config = LettaConfig("remote", LettaConfig.Mode.SELF_HOSTED, "http://unused.invalid")
        val gateway = createDefaultDesktopChatGateway(
            config = config,
            appServerConfig = DesktopAppServerRuntimeConfig(enabled = true, serverUrl = "ws://remote:4500"),
            appServerGatewayFactory = DesktopAppServerChatGatewayFactory { _, _ -> DesktopLettaHttpChatGateway(config) },
            localRuntime = runtime,
        )

        assertEquals(0, runtime.acquireCount)
        assertEquals(0, runtime.closeCount)
        assertIs<DesktopLettaHttpChatGateway>(gateway).close()
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

    private class FakeLocalRuntime(
        private val url: String = "ws://127.0.0.1:49920",
    ) : DesktopLocalRuntimeLifecycle {
        var acquireCount = 0
        var closeCount = 0

        override fun acquire(): DesktopLocalRuntimeLease {
            acquireCount += 1
            return object : DesktopLocalRuntimeLease {
                override val serverUrl: String = url
                private var closed = false
                override fun close() {
                    if (!closed) {
                        closed = true
                        closeCount += 1
                    }
                }
            }
        }

        override fun close() {
            closeCount += 1
        }
    }
}
