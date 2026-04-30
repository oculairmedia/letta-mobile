package com.letta.mobile.debug

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.screens.config.ConfigViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@Tag("integration")
class AutomationAuthBootstrapTest {
    private lateinit var context: Context
    private lateinit var stagingPrefs: android.content.SharedPreferences
    private val savedConfigs = mutableListOf<LettaConfig>()
    private var activeConfig: LettaConfig? = null
    private var gatewayEnabled: Boolean? = null
    private var gatewayBaseUrl: String? = null
    private var gatewayApiKey: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stagingPrefs = context.getSharedPreferences(AutomationAuthBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        stagingPrefs.edit().clear().commit()
        savedConfigs.clear()
        activeConfig = null
        gatewayEnabled = null
        gatewayBaseUrl = null
        gatewayApiKey = null
    }

    @After
    fun tearDown() {
        stagingPrefs.edit().clear().commit()
    }

    @Test
    fun importPendingConfig_promotesPayloadIntoSettingsRepositoryAndClearsStagingPref() = runBlocking {
        stagePayload(
            """
            {"serverUrl":"${ConfigViewModel.DEFAULT_CLOUD_URL}","accessToken":"test-token"}
            """.trimIndent(),
        )

        AutomationAuthBootstrap.importPendingConfig(context, ::recordConfig)

        assertEquals("automation-auth", activeConfig?.id)
        assertEquals(LettaConfig.Mode.CLOUD, activeConfig?.mode)
        assertEquals(ConfigViewModel.DEFAULT_CLOUD_URL, activeConfig?.serverUrl)
        assertEquals("test-token", activeConfig?.accessToken)
        assertNull(stagingPrefs.getString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, null))
    }

    @Test
    fun importPendingConfig_normalizesSelfHostedPayloadAndOverridesExistingConfig() = runBlocking {
        recordConfig(
            LettaConfig(
                id = "existing",
                mode = LettaConfig.Mode.CLOUD,
                serverUrl = ConfigViewModel.DEFAULT_CLOUD_URL,
                accessToken = "stale-token",
            )
        )
        stagePayload(
            """
            {"serverUrl":"demo.letta.internal/","accessToken":" fresh-token ","configId":"automation-auth"}
            """.trimIndent(),
        )

        AutomationAuthBootstrap.importPendingConfig(context, ::recordConfig)

        assertEquals("automation-auth", activeConfig?.id)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, activeConfig?.mode)
        assertEquals("https://demo.letta.internal", activeConfig?.serverUrl)
        assertEquals("fresh-token", activeConfig?.accessToken)
        assertTrue(savedConfigs.any { it.id == "automation-auth" })
    }

    @Test
    fun importPendingConfig_populatesGatewaySettingsWhenProvided() = runBlocking {
        stagePayload(
            """
            {"serverUrl":"demo.letta.internal/","accessToken":"fresh-token","gatewayUrl":"192.168.50.90:8407/api/v1/agent-gateway","gatewayApiKey":" gateway-secret ","gatewayEnabled":true}
            """.trimIndent(),
        )

        AutomationAuthBootstrap.importPendingConfig(
            context = context,
            saveConfig = ::recordConfig,
            setGatewayEnabled = { gatewayEnabled = it },
            setGatewayBaseUrl = { gatewayBaseUrl = it },
            setGatewayApiKey = { gatewayApiKey = it },
        )

        assertEquals("ws://192.168.50.90:8407/api/v1/agent-gateway", gatewayBaseUrl)
        assertEquals("gateway-secret", gatewayApiKey)
        assertEquals(true, gatewayEnabled)
    }

    @Test
    fun importPendingConfig_dropsInvalidPayloadAndLeavesExistingConfigUntouched() = runBlocking {
        recordConfig(
            LettaConfig(
                id = "existing",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "https://stable.example",
                accessToken = "stable-token",
            )
        )
        stagePayload("{\"serverUrl\":\"\",\"accessToken\":\"\"}")

        AutomationAuthBootstrap.importPendingConfig(context, ::recordConfig)

        assertEquals("existing", activeConfig?.id)
        assertEquals("stable-token", activeConfig?.accessToken)
        assertNull(stagingPrefs.getString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, null))
    }

    @Test
    fun importPendingConfig_rejects_partialGatewayPayload() = runBlocking {
        stagePayload(
            """
            {"serverUrl":"demo.letta.internal/","accessToken":"fresh-token","gatewayUrl":"ws://192.168.50.90:8407/api/v1/agent-gateway"}
            """.trimIndent(),
        )

        AutomationAuthBootstrap.importPendingConfig(
            context = context,
            saveConfig = ::recordConfig,
            setGatewayEnabled = { gatewayEnabled = it },
            setGatewayBaseUrl = { gatewayBaseUrl = it },
            setGatewayApiKey = { gatewayApiKey = it },
        )

        assertNull(gatewayBaseUrl)
        assertNull(gatewayApiKey)
        assertNull(gatewayEnabled)
        assertNull(stagingPrefs.getString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, null))
    }

    @Test
    fun importPendingConfig_honorsExplicitGatewayDisabledFlag() = runBlocking {
        stagePayload(
            """
            {"serverUrl":"demo.letta.internal/","accessToken":"fresh-token","gatewayUrl":"ws://192.168.50.90:8407/api/v1/agent-gateway","gatewayApiKey":"gateway-secret","gatewayEnabled":false}
            """.trimIndent(),
        )

        AutomationAuthBootstrap.importPendingConfig(
            context = context,
            saveConfig = ::recordConfig,
            setGatewayEnabled = { gatewayEnabled = it },
            setGatewayBaseUrl = { gatewayBaseUrl = it },
            setGatewayApiKey = { gatewayApiKey = it },
        )

        assertEquals(false, gatewayEnabled)
        assertEquals("ws://192.168.50.90:8407/api/v1/agent-gateway", gatewayBaseUrl)
        assertEquals("gateway-secret", gatewayApiKey)
    }

    private fun stagePayload(jsonPayload: String) {
        val encoded = Base64.getEncoder().encodeToString(jsonPayload.toByteArray())
        stagingPrefs.edit().putString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, encoded).commit()
    }

    private fun recordConfig(config: LettaConfig) {
        val existingIndex = savedConfigs.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            savedConfigs[existingIndex] = config
        } else {
            savedConfigs.add(config)
        }
        activeConfig = config
    }
}
