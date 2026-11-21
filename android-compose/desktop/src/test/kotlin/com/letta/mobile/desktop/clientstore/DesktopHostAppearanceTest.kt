@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.desktop.clientstore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStatePublisher
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateRoutes
import com.letta.mobile.desktop.DesktopMaterialTheme
import com.letta.mobile.desktop.LocalDesktopHostAppearance
import com.letta.mobile.ui.settings.AppearanceSettingsSection
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.settings.rememberHostAppearanceSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The desktop's binding of the shared appearance: the window's theme and the Settings rows are the host's appearance,
 * and the shell's transport is what it reads it over. (The host's own behaviour is asserted against the production routes
 * in sharedLogic; here the wiring the desktop adds is.)
 */
class DesktopHostAppearanceTest {
    @Test
    fun theDesktopThemeDrawsTheHostsAppearanceNotTheOs() = runComposeUiTest {
        var background = Color.Unspecified
        setContent {
            CompositionLocalProvider(LocalDesktopHostAppearance provides FixedAppearance(AppearanceValues(theme = "DARK"))) {
                DesktopMaterialTheme {
                    background = MaterialTheme.colorScheme.background
                    Text("desktop")
                }
            }
        }
        waitForIdle()

        assertTrue(background.luminance() < DARK_BELOW, "the host's DARK is drawn")
    }

    @Test
    fun theDesktopSettingsOfferThemeAndPresetButNoDynamicColour() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDesktopHostAppearance provides FixedAppearance(AppearanceValues())) {
                AppearanceSettingsSection(rememberHostAppearanceSettings(LocalDesktopHostAppearance.current))
            }
        }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme").assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme-preset").assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-dynamic-color").assertDoesNotExist()
    }

    @Test
    fun theTransportTheShellAttachesIsWhatTheDesktopReadsAndChangesTheHostOver() = runBlocking {
        val host = HostTransport()
        val appearance = DesktopHostAppearance(InMemorySecureSettingsStore())
        val following = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            following.launch { appearance.run() }
            assertEquals(ShellStateEditOutcome.OFFLINE, appearance.setTheme("DARK"), "no transport attached yet: refused, not queued")

            appearance.attach(host)
            val outcome = withTimeout(TIMEOUT_MS) {
                var tried = appearance.setTheme("DARK")
                while (tried == ShellStateEditOutcome.OFFLINE) {
                    delay(POLL_MS)
                    tried = appearance.setTheme("DARK")
                }
                tried
            }

            assertEquals(ShellStateEditOutcome.APPLIED, outcome)
            assertEquals(JsonPrimitive("DARK"), host.heldValue("appearance.theme"))
            assertEquals("DARK", withTimeout(TIMEOUT_MS) { appearance.values.first { it.theme == "DARK" } }.theme)
        } finally {
            following.cancel()
        }
    }

    /**
     * A desktop's channel transport to a real host: `admin_rpc` goes through the production capability gate for the desktop
     * role and the production shell-state routes over a real state file.
     */
    private class HostTransport : NoOpChannelTransport() {
        private val router = AdminRpcRouter().also {
            ShellStateRoutes.forHost(Files.createTempDirectory("desktop-host-state"), ShellStatePublisher(CoroutineScope(Dispatchers.Unconfined))).register(it)
        }
        override val state: StateFlow<ChannelTransportState> = MutableStateFlow(
            ChannelTransportState.Connected(serverId = "iroh-app-server", sessionId = "host", deviceId = "desktop", connectionEpoch = 1),
        )
        override val hostCapabilities: Set<String> get() = router.featureCapabilities

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val required = IrohPeerCapabilities.forAdminMethod(method)
            if (!IrohPeerCapabilities.isAllowed(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE, required)) {
                return AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = false, error = "forbidden")
            }
            val context = AdminRpcRequestContext(authenticated = true, authenticatedEndpointId = "desktop")
            val raw = router.dispatch(AdminRpcInvocation("r", method, appServerAdminRpcParams(path, body), context))
            val reply = Json.parseToJsonElement(raw).jsonObject
            return AppServerInboundFrame.AdminRpcResponse(
                requestId = "r",
                success = reply.getValue("success").jsonPrimitive.boolean,
                result = reply["result"],
                error = reply["error"]?.jsonPrimitive?.content,
            )
        }

        /** The value the host holds under [key], read through the route like any client. */
        suspend fun heldValue(key: String): JsonElement? {
            val entries = adminRpc(ShellStateWire.GET, "", null).result!!.jsonObject.getValue("entries").jsonObject
            return entries[key]?.jsonObject?.get("value")
        }
    }

    /** Appearance a test holds fixed. */
    private class FixedAppearance(private val chosen: AppearanceValues) : HostAppearance {
        override val values = MutableStateFlow(chosen)
        override val current: AppearanceValues get() = chosen
        override suspend fun setTheme(name: String) = ShellStateEditOutcome.OFFLINE
        override suspend fun setThemePreset(name: String) = ShellStateEditOutcome.OFFLINE
        override suspend fun setDynamicColor(enabled: Boolean) = ShellStateEditOutcome.OFFLINE
        override suspend fun run() = Unit
        override suspend fun clearLocal() = Unit
    }

    private companion object {
        const val DARK_BELOW = 0.2f
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
