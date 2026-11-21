@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairLink
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.pairing.InstallAndPairBindings
import com.letta.mobile.ui.pairing.PairingTags
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pairing card on Settings > Backend is one more Settings card: drawn by [SettingsSection] under
 * the page's Pairing tag, its link field keeps the pairing tags, and a pasted link stops at the trust
 * prompt inside the card. Snapshots at every checked width (placeholder host and links only) land in
 * build/settings-pairing-snapshots for review.
 */
class SettingsPairingCardTest {
    private val nodeId = "ab".repeat(32)
    private val config = LettaConfig(
        id = "default",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "ws://host.example.invalid:4500",
        accessToken = null,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun link(): String {
        val json = """{"version":1,"node_id":"$nodeId","signed_secret":"invite:${"5".repeat(64)}","expires_at_ms":900000,"signature":"s"}"""
        val envelope = "letta-qr-v1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
        return PairLink(envelope, "h:1").encode()
    }

    private fun bindings(dials: MutableList<String> = mutableListOf()) = InstallAndPairBindings(
        joinFlow = {
            PairingFlow(
                redeemer = { invite, _ ->
                    dials += invite.nodeId
                    RedeemOutcome.Paired(PairedHost(invite.nodeId, invite.nodeId), alreadyPaired = false)
                },
                store = {},
                deviceName = "Placeholder device",
                nowMs = { 0L },
            )
        },
        qrEncoder = QrEncoder { QrModules(1) { _, _ -> true } },
        rpc = null,
        agents = emptyList(),
        onOpenChat = {},
        nowMs = { 0L },
    )

    private fun ComposeUiTest.page(width: Dp, dark: Boolean, pairing: InstallAndPairBindings = bindings()) {
        val host = if (width < TOUCH_BELOW) LettaHost(input = LettaInput.Touch) else LettaHost.Pointer
        setContent {
            ProvideLettaHost(host) {
                SharedMaterialTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        val services = SettingsPageServices(BackendSettingsActions(onConfigSaved = {}), pairing = pairing)
                        SettingsPage(SettingsPageState(config), services)
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.pasteLink() {
        onNodeWithTag(PairingTags.PASTE_FIELD).performScrollTo().performTextInput("  ${link()}  ")
        onNodeWithTag(PairingTags.PASTE_SUBMIT).performClick()
        waitForIdle()
    }

    @Test
    fun thePairingCardIsASettingsSectionThatKeepsItsTagsAndStopsAtTheTrustPrompt() = runDesktopComposeUiTest(width = 400, height = HEIGHT) {
        val dials = mutableListOf<String>()
        page(400.dp, dark = true, pairing = bindings(dials))

        onNodeWithTag(SettingsPageTags.PAIRING).performScrollTo().assertExists()
        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "pairing").assertExists()
        onNodeWithTag(PairingTags.PASTE_CLIPBOARD).assertExists()
        onNodeWithTag(PairingTags.PASTE_SUBMIT).assertIsNotEnabled()
        onNodeWithTag(PairingTags.PASTE_FIELD).performTextInput("meridian://pair?qr=x")
        onNodeWithTag(PairingTags.PASTE_SUBMIT).assertIsEnabled()
        onNodeWithTag(PairingTags.PASTE_FIELD).performTextClearance()

        onNodeWithTag(PairingTags.STATUS).assertDoesNotExist()
        pasteLink()
        onNodeWithTag(PairingTags.TRUST_PROMPT).assertExists()
        onNodeWithTag(PairingTags.PASTE_FIELD).assertIsNotEnabled()
        assertEquals(emptyList(), dials)

        onNodeWithTag(PairingTags.TRUST_CONNECT).performClick()
        waitForIdle()
        assertEquals(listOf(nodeId), dials)
    }

    private fun snapshot(width: Dp, scene: String) = runDesktopComposeUiTest(width = width.value.toInt(), height = HEIGHT) {
        page(width, dark = true)
        if (scene == TRUST) pasteLink()
        onNodeWithTag(SettingsPageTags.PAIRING).performScrollTo()
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val out = File("build/settings-pairing-snapshots").apply { mkdirs() }.resolve("$scene-${width.value.toInt()}dp-dark.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun idleAtEveryWidth() = WIDTHS.forEach { snapshot(it, IDLE) }

    @Test
    fun trustPromptAtEveryWidth() = WIDTHS.forEach { snapshot(it, TRUST) }

    private companion object {
        val WIDTHS = listOf(360.dp, 400.dp, 700.dp, 760.dp, 1200.dp)
        val TOUCH_BELOW = 760.dp
        const val HEIGHT = 1000
        const val IDLE = "idle"
        const val TRUST = "trust"
    }
}
