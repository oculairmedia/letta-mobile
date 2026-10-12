@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.pairing.MintedPairInvite
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairInviteUiState
import com.letta.mobile.data.pairing.PairedDevice
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.QrModules
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-dpv7m.2: the shared joining-device and inviter surfaces. */
class PairingSurfacesUiTest {
    private val nodeId = "ab".repeat(32)

    @Test
    fun failureShowsTheTypedMessageAndScanAgain() = runComposeUiTest {
        var rescans = 0
        setContent {
            MaterialTheme {
                PairingStatusCard(PairingState.Failed(PairingError.EXPIRED), onScanAgain = { rescans++ }, onOpenChat = {})
            }
        }
        onNodeWithTag(PairingTags.ERROR).assertTextContains(PairingStrings.error(PairingError.EXPIRED))
        onNodeWithTag(PairingTags.SCAN_AGAIN).performClick()
        assertEquals(1, rescans)
    }

    @Test
    fun pairedOffersToOpenChat() = runComposeUiTest {
        var opened = false
        setContent {
            MaterialTheme {
                PairingStatusCard(
                    PairingState.Paired(PairedHost(nodeId, nodeId), alreadyPaired = false),
                    onScanAgain = {},
                    onOpenChat = { opened = true },
                )
            }
        }
        onNodeWithText(PairingStrings.PAIRED_BODY).assertIsDisplayed()
        onNodeWithTag(PairingTags.OPEN_CHAT).performClick()
        assertEquals(true, opened)
    }

    @Test
    fun idleRendersNothingSoTheScannerShowsThrough() = runComposeUiTest {
        setContent { MaterialTheme { PairingStatusCard(PairingState.Idle, onScanAgain = {}, onOpenChat = {}) } }
        onNodeWithTag(PairingTags.STATUS).assertDoesNotExist()
    }

    @Test
    fun pastedLinkIsSubmittedTrimmed() = runComposeUiTest {
        var submitted: String? = null
        setContent { MaterialTheme { PairCodeEntry(onSubmit = { submitted = it }) } }
        onNodeWithTag(PairingTags.PASTE_FIELD).performTextInput("  meridian://pair?qr=letta-qr-v1.x  ")
        onNodeWithTag(PairingTags.PASTE_SUBMIT).performClick()
        assertEquals("meridian://pair?qr=letta-qr-v1.x", submitted)
    }

    private fun actions(onMint: (PairInviteRequest) -> Unit = {}, onRevoke: (String) -> Unit = {}) =
        PairDeviceActions(onMint = onMint, onRevokeDevice = onRevoke, onRevokeInvite = {}, onDismissInvite = {})

    @Test
    fun inviterMintsForTheSelectedAgentsOnly() = runComposeUiTest {
        var request: PairInviteRequest? = null
        setContent {
            MaterialTheme {
                PairDevicePanel(
                    state = PairInviteUiState(),
                    agents = listOf(AgentChoice("agent-1", "Meridian"), AgentChoice("agent-2", "Worker")),
                    actions = actions(onMint = { request = it }),
                    nowMs = 0L,
                )
            }
        }
        onNodeWithTag(PairingTags.agentOption("agent-1")).performClick()
        onNodeWithTag(PairingTags.INVITE_SHOW).performClick()
        assertEquals(setOf("agent-1"), request?.agentIds)
        assertEquals(PairInviteRequest.ROLE_CHAT, request?.role)
    }

    @Test
    fun inviterShowsTheCodeAcceptanceAndCanRevokeADevice() = runComposeUiTest {
        var revoked: String? = null
        val device = PairedDevice(nodeId, "pixel", pairedAtMs = 1, lastSeenAtMs = null, capabilities = emptySet(), agentIds = setOf("agent-1"))
        setContent {
            MaterialTheme {
                PairDevicePanel(
                    state = PairInviteUiState(
                        invite = MintedPairInvite("meridian://pair?qr=x", "inv-1", expiresAtMs = 300_000, agentIds = null),
                        qr = QrModules(21) { r, c -> (r + c) % 2 == 0 },
                        devices = listOf(device),
                        accepted = device,
                    ),
                    agents = emptyList(),
                    actions = actions(onRevoke = { revoked = it }),
                    nowMs = 0L,
                )
            }
        }
        onNodeWithTag(PairingTags.INVITE_QR).assertIsDisplayed()
        onNodeWithTag(PairingTags.INVITE_ACCEPTED).assertTextContains("pixel", substring = true)
        onNodeWithTag(PairingTags.revokeDevice(nodeId)).performClick()
        assertEquals(nodeId, revoked)
    }
}
