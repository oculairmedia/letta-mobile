@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.pairing.PairLink
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.RedeemOutcome
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-dpv7m.2/.5: the joining-device surfaces — trust prompt, states, scanner fallbacks. */
class PairingSurfacesUiTest {
    private val nodeId = "ab".repeat(32)

    private class Recorder {
        val events = mutableListOf<String>()
        val submitted = mutableListOf<String>()

        fun actions() = PairingScanActions(
            onSubmit = { submitted += it },
            onConfirm = { events += "confirm" },
            onCancel = { events += "cancel" },
            onRetry = { events += "retry" },
            onScanAgain = { events += "scanAgain" },
            onOpenChat = { events += "openChat" },
        )
    }

    @Test
    fun failureShowsOneDistinctLineAndScanAgain() = runComposeUiTest {
        val r = Recorder()
        setContent { MaterialTheme { PairingStatusCard(PairingState.Failed(PairingError.EXPIRED), r.actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.ERROR).assertTextContains(PairingStrings.error(PairingError.EXPIRED))
        onNodeWithTag(PairingTags.RETRY).assertDoesNotExist()
        onNodeWithTag(PairingTags.SCAN_AGAIN).performClick()
        assertEquals(listOf("scanAgain"), r.events)
    }

    @Test
    fun anUnreachableHostOffersRetry() = runComposeUiTest {
        val r = Recorder()
        setContent {
            MaterialTheme { PairingStatusCard(PairingState.Failed(PairingError.HOST_UNREACHABLE, retryable = true), r.actions(), nowMs = 0) }
        }
        onNodeWithTag(PairingTags.RETRY).performClick()
        assertEquals(listOf("retry"), r.events)
    }

    @Test
    fun pairedNamesTheDeviceAndOffersToOpenChat() = runComposeUiTest {
        val r = Recorder()
        setContent {
            MaterialTheme {
                PairingStatusCard(PairingState.Paired(PairedHost(nodeId, nodeId), alreadyPaired = false, deviceName = "Pixel 9"), r.actions(), nowMs = 0)
            }
        }
        onNodeWithText(PairingStrings.pairedDeviceLine("Pixel 9")).assertIsDisplayed()
        onNodeWithTag(PairingTags.OPEN_CHAT).performClick()
        assertEquals(listOf("openChat"), r.events)
    }

    @Test
    fun trustPromptShowsTheShortHostIdAndExpiryWithConnectAndCancel() = runComposeUiTest {
        val r = Recorder()
        setContent { MaterialTheme { PairingStatusCard(PairingState.ConfirmHost(nodeId, expiresAtMs = 272_000), r.actions(), nowMs = 0) } }
        onNodeWithText(PairingStrings.trustTitle("ababab…ababab")).assertIsDisplayed()
        onNodeWithText(PairingStrings.trustExpiresLine(272_000)).assertTextContains("4:32", substring = true)
        onNodeWithTag(PairingTags.TRUST_CANCEL).performClick()
        onNodeWithTag(PairingTags.TRUST_CONNECT).performClick()
        assertEquals(listOf("cancel", "confirm"), r.events)
    }

    @Test
    fun verifyingCanBeCancelled() = runComposeUiTest {
        val r = Recorder()
        setContent { MaterialTheme { PairingStatusCard(PairingState.Pairing("abababab"), r.actions(), nowMs = 0) } }
        onNodeWithText(PairingStrings.VERIFYING_STEP_CONTACT).assertIsDisplayed()
        onNodeWithTag(PairingTags.VERIFY_CANCEL).performClick()
        assertEquals(listOf("cancel"), r.events)
    }

    @Test
    fun idleRendersNothingSoTheScannerShowsThrough() = runComposeUiTest {
        setContent { MaterialTheme { PairingStatusCard(PairingState.Idle, Recorder().actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.STATUS).assertDoesNotExist()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun link(): String {
        val json = """{"version":1,"node_id":"$nodeId","signed_secret":"invite:${"5".repeat(64)}","expires_at_ms":900000,"signature":"s"}"""
        val envelope = "letta-qr-v1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
        return PairLink(envelope, "h:1").encode()
    }

    @Test
    fun aPastedLinkStopsAtTheTrustPromptAndOnlyConnectDials() = runComposeUiTest {
        var dials = 0
        val flow = PairingFlow(
            redeemer = { invite, _ -> dials++; RedeemOutcome.Paired(PairedHost(invite.nodeId, "${invite.nodeId}@h:1"), alreadyPaired = false) },
            store = {},
            deviceName = "desk",
            nowMs = { 0L },
        )
        setContent {
            val scope = rememberCoroutineScope()
            MaterialTheme {
                val state = flow.state.collectAsState().value
                PairingScanContent(state, ScanCamera.Unavailable, pairingScanActions(flow, scope) {}, nowMs = 0)
            }
        }
        onNodeWithTag(PairingTags.PASTE_FIELD).performTextInput("  ${link()}  ")
        onNodeWithTag(PairingTags.PASTE_SUBMIT).performClick()
        onNodeWithTag(PairingTags.TRUST_PROMPT).assertIsDisplayed()
        assertEquals(0, dials, "nothing is dialled before the user confirms the host")
        onNodeWithTag(PairingTags.TRUST_CONNECT).performClick()
        waitForIdle()
        assertEquals(1, dials)
        onNodeWithTag(PairingTags.OPEN_CHAT).assertIsDisplayed()
    }

    @Test
    fun desktopAndWebArePasteOnly() = runComposeUiTest {
        setContent { MaterialTheme { PairingScanContent(PairingState.Idle, ScanCamera.Unavailable, Recorder().actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.PASTE_FIELD).assertIsDisplayed()
        onNodeWithTag(PairingTags.CAMERA_AREA).assertDoesNotExist()
        onNodeWithTag(PairingTags.PASTE_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun cameraRationaleComesBeforeTheOsPrompt() = runComposeUiTest {
        var requested = 0
        setContent {
            MaterialTheme {
                PairingScanContent(PairingState.Idle, ScanCamera(CameraAccess.NEEDS_RATIONALE, onRequestAccess = { requested++ }), Recorder().actions(), nowMs = 0)
            }
        }
        onNodeWithText(PairingStrings.CAMERA_RATIONALE_BODY).assertIsDisplayed()
        onNodeWithTag(PairingTags.CAMERA_ALLOW).performClick()
        assertEquals(1, requested)
    }

    @Test
    fun deniedCameraOffersSettingsAndPasteInstead() = runComposeUiTest {
        var settings = 0
        val r = Recorder()
        setContent {
            MaterialTheme { PairingScanContent(PairingState.Idle, ScanCamera(CameraAccess.DENIED, onOpenSettings = { settings++ }), r.actions(), nowMs = 0) }
        }
        onNodeWithText(PairingStrings.CAMERA_DENIED_TITLE).assertIsDisplayed()
        onNodeWithTag(PairingTags.OPEN_SETTINGS).performClick()
        assertEquals(1, settings)
        onNodeWithTag(PairingTags.PASTE_TOGGLE).assertTextContains(PairingStrings.PASTE_INSTEAD).performClick()
        onNodeWithTag(PairingTags.PASTE_FIELD).performTextInput("meridian://pair?qr=x")
        onNodeWithTag(PairingTags.PASTE_SUBMIT).performClick()
        assertEquals(listOf("meridian://pair?qr=x"), r.submitted)
    }

    @Test
    fun grantedCameraShowsViewfinderTorchAndForwardsFramesOnlyWhileIdle() = runComposeUiTest {
        var torchToggles = 0
        var deliver: ((String) -> Unit)? = null
        val r = Recorder()
        var state: PairingState by mutableStateOf(PairingState.Idle)
        val camera = ScanCamera(
            CameraAccess.GRANTED,
            torch = TorchControl(on = false) { torchToggles++ },
            preview = { onDecoded -> deliver = onDecoded },
        )
        setContent { MaterialTheme { PairingScanContent(state, camera, r.actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.CAMERA_AREA).assertIsDisplayed()
        onNodeWithTag(PairingTags.TORCH).performClick()
        assertEquals(1, torchToggles)
        deliver!!("frame-1")
        state = PairingState.ConfirmHost(nodeId, 900_000)
        waitForIdle()
        deliver!!("frame-2")
        assertEquals(listOf("frame-1"), r.submitted)
        onNodeWithTag(PairingTags.TRUST_PROMPT).assertIsDisplayed()
    }

    @Test
    fun pasteSubmitIsDisabledWhileAPromptIsOpen() = runComposeUiTest {
        setContent {
            MaterialTheme { PairingScanContent(PairingState.ConfirmHost(nodeId, 900_000), ScanCamera.Unavailable, Recorder().actions(), nowMs = 0) }
        }
        onNodeWithTag(PairingTags.PASTE_FIELD).assertIsNotEnabled()
    }

    @Test
    fun pastedLinkIsSubmittedTrimmed() = runComposeUiTest {
        var submitted: String? = null
        setContent { MaterialTheme { PairCodeEntry(onSubmit = { submitted = it }) } }
        onNodeWithTag(PairingTags.PASTE_SUBMIT).assertIsNotEnabled()
        onNodeWithTag(PairingTags.PASTE_FIELD).performTextInput("  meridian://pair?qr=letta-qr-v1.x  ")
        onNodeWithTag(PairingTags.PASTE_SUBMIT).assertIsEnabled().performClick()
        assertEquals("meridian://pair?qr=letta-qr-v1.x", submitted)
    }
}

