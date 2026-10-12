@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.pairing.MintedPairInvite
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairInviteUiState
import com.letta.mobile.data.pairing.PairedDevice
import com.letta.mobile.data.pairing.PendingInvite
import com.letta.mobile.data.pairing.QrModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-dpv7m.5: the inviter's card states and the paired-devices list. */
class PairInviteSurfacesUiTest {
    private val link = "meridian://pair?qr=letta-qr-v1.secret&dial=h%3A1"
    private val invite = MintedPairInvite(link, "inv-1", expiresAtMs = 300_000, agentIds = null)
    private val qr = QrModules(21) { r, c -> (r + c) % 2 == 0 }
    private val chatCaps = setOf("chat.read", "chat.send", "conversation.manage")

    private fun device(id: String, name: String = id, agents: Set<String>? = null, caps: Set<String> = chatCaps) =
        PairedDevice(id, name, pairedAtMs = 1, lastSeenAtMs = null, capabilities = caps, agentIds = agents)

    private class Recorder {
        val events = mutableListOf<String>()
        var minted: PairInviteRequest? = null

        fun actions(share: Boolean = true) = PairDeviceActions(
            onMint = { minted = it },
            onNewCode = { events += "newCode" },
            onChangeAccess = { events += "changeAccess" },
            onRevokeDevice = { events += "revoke:$it" },
            onRevokeAllOthers = { events += "revokeAll" },
            onRevokeInvite = { events += "cancel:$it" },
            onShareLink = if (share) { link -> events += "share:$link" } else null,
        )
    }

    @Test
    fun scopeDefaultsToLeastPrivilegeAndNeedsAnAgentChosen() = runComposeUiTest {
        val r = Recorder()
        setContent {
            MaterialTheme {
                PairDevicePanel(PairInviteUiState(), listOf(AgentChoice("a1", "Sage"), AgentChoice("a2", "Atlas")), r.actions(), nowMs = 0)
            }
        }
        onNodeWithTag(PairingTags.INVITE_SHOW).assertIsNotEnabled()
        onNodeWithTag(PairingTags.agentOption("a1")).performClick()
        onNodeWithText("Access: Sage · chat only · expires in 5 min").assertIsDisplayed()
        onNodeWithTag(PairingTags.INVITE_SHOW).assertIsEnabled().performClick()
        assertEquals(setOf("a1"), r.minted?.agentIds)
        assertEquals(PairInviteRequest.ROLE_CHAT, r.minted?.role)
        assertEquals(PairInviteRequest.DEFAULT_TTL_MS, r.minted?.ttlMs)
    }

    @Test
    fun allAgentsAndSettingsChangesAreExplicitOptIns() = runComposeUiTest {
        val r = Recorder()
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(), listOf(AgentChoice("a1", "Sage")), r.actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.INVITE_ALL_AGENTS).performClick()
        onNodeWithTag(PairingTags.INVITE_SETTINGS).performClick()
        onNodeWithTag(PairingTags.INVITE_SHOW).performClick()
        assertEquals(emptySet(), r.minted?.agentIds)
        assertEquals(PairInviteRequest.ROLE_DESKTOP, r.minted?.role)
    }

    @Test
    fun showingCodeHasQrCountdownAndAOneTapLinkEquivalent() = runComposeUiTest {
        val r = Recorder()
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(invite = invite, qr = qr), emptyList(), r.actions(), nowMs = 28_000) } }
        onNodeWithTag(PairingTags.INVITE_QR).assertIsDisplayed()
        onNodeWithTag(PairingTags.INVITE_COUNTDOWN).assertTextContains("Expires in 4:32")
        onNodeWithTag(PairingTags.INVITE_LINK).assertDoesNotExist()
        onNodeWithTag(PairingTags.INVITE_TOGGLE_LINK).assertTextContains(PairingStrings.SHOW_LINK).performClick()
        onNodeWithTag(PairingTags.INVITE_LINK).assertTextContains(link)
        onNodeWithTag(PairingTags.INVITE_QR).assertDoesNotExist()
        onNodeWithTag(PairingTags.INVITE_SHARE).performClick()
        onNodeWithTag(PairingTags.INVITE_NEW_CODE).performClick()
        onNodeWithTag(PairingTags.INVITE_CANCEL).performClick()
        assertEquals(listOf("share:$link", "newCode", "cancel:inv-1"), r.events)
    }

    @Test
    fun noShareSheetMeansCopyOnly() = runComposeUiTest {
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(invite = invite, qr = qr), emptyList(), Recorder().actions(share = false), nowMs = 0) } }
        onNodeWithTag(PairingTags.INVITE_COPY).assertIsDisplayed()
        onNodeWithTag(PairingTags.INVITE_SHARE).assertDoesNotExist()
    }

    @Test
    fun theLastThirtySecondsSayExpiringInWords() = runComposeUiTest {
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(invite = invite, qr = qr), emptyList(), Recorder().actions(), nowMs = 290_000) } }
        onNodeWithTag(PairingTags.INVITE_COUNTDOWN).assertTextContains(PairingStrings.EXPIRING_SOON, substring = true)
    }

    @Test
    fun expiredCodeIsMaskedAndOffersNewCodeNotAnAutoRefresh() = runComposeUiTest {
        val r = Recorder()
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(invite = invite, qr = qr), emptyList(), r.actions(), nowMs = 300_000) } }
        onNodeWithTag(PairingTags.INVITE_EXPIRED).assertTextContains(PairingStrings.CODE_EXPIRED)
        onNodeWithTag(PairingTags.INVITE_LINK).assertDoesNotExist()
        onNodeWithTag(PairingTags.INVITE_COPY).assertDoesNotExist()
        onNodeWithTag(PairingTags.INVITE_NEW_CODE).performClick()
        assertEquals(listOf("newCode"), r.events)
        assertNull(r.minted)
    }

    @Test
    fun cancelledCodeSaysSo() = runComposeUiTest {
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(cancelled = true), emptyList(), Recorder().actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.INVITE_CANCELLED).assertIsDisplayed()
    }

    @Test
    fun redeemedShowsTheNewDevice() = runComposeUiTest {
        val pixel = device("p", "Pixel")
        setContent { MaterialTheme { PairDevicePanel(PairInviteUiState(accepted = pixel, devices = listOf(pixel)), emptyList(), Recorder().actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.INVITE_ACCEPTED).assertTextContains("Pixel", substring = true)
    }

    @Test
    fun devicesAreGroupedWithHonestScopeChipsAndThisDeviceCannotBeRevoked() = runComposeUiTest {
        val state = PairInviteUiState(
            devices = listOf(device("me", "This phone"), device("lap", "Laptop", agents = setOf("a1")), device("tab", "Tablet", caps = setOf("admin.full"))),
            selfNodeId = "me",
        )
        setContent { MaterialTheme { PairedDevicesList(state, Recorder().actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.THIS_DEVICE).assertIsDisplayed()
        onNodeWithTag(PairingTags.revokeDevice("me")).assertDoesNotExist()
        onNodeWithText(PairingStrings.ACTIVE_NOW).assertIsDisplayed()
        onNodeWithText("Lists 1 agent").assertIsDisplayed()
        onNodeWithText("Full admin").assertIsDisplayed()
        onNodeWithTag(PairingTags.AGENT_SCOPE_NOTE).assertIsDisplayed()
    }

    @Test
    fun revokeNeedsConfirmationAndReadsRevokingUntilTheHostAnswers() = runComposeUiTest {
        val r = Recorder()
        var state by mutableStateOf(PairInviteUiState(devices = listOf(device("lap", "Laptop"))))
        setContent { MaterialTheme { PairedDevicesList(state, r.actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.revokeDevice("lap")).performClick()
        assertEquals(emptyList(), r.events, "one tap never revokes")
        onNodeWithText(PairingStrings.revokeConfirm("Laptop")).assertIsDisplayed()
        onNodeWithTag(PairingTags.revokeConfirm("lap")).performClick()
        assertEquals(listOf("revoke:lap"), r.events)
        state = state.copy(revoking = setOf("lap"))
        onNodeWithTag(PairingTags.revoking("lap")).assertTextContains(PairingStrings.REVOKING)
        onNodeWithTag(PairingTags.revokeDevice("lap")).assertDoesNotExist()
    }

    @Test
    fun revokeAllOthersOnlyWithThisDeviceKnownAndAfterConfirm() = runComposeUiTest {
        val r = Recorder()
        val state = PairInviteUiState(devices = listOf(device("me"), device("a"), device("b")), selfNodeId = "me")
        setContent { MaterialTheme { PairedDevicesList(state, r.actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.REVOKE_ALL_OTHERS).performClick()
        assertEquals(emptyList(), r.events)
        onNodeWithTag(PairingTags.REVOKE_ALL_CONFIRM).performClick()
        assertEquals(listOf("revokeAll"), r.events)
    }

    @Test
    fun noRevokeAllWhenThisDeviceIsUnknown() = runComposeUiTest {
        val state = PairInviteUiState(devices = listOf(device("a"), device("b")))
        setContent { MaterialTheme { PairedDevicesList(state, Recorder().actions(), nowMs = 0) } }
        onNodeWithTag(PairingTags.REVOKE_ALL_OTHERS).assertDoesNotExist()
    }

    @Test
    fun pendingInvitesCanBeCancelled() = runComposeUiTest {
        val r = Recorder()
        val state = PairInviteUiState(pending = listOf(PendingInvite("inv-9", "Old phone", expiresAtMs = 60_000)))
        setContent { MaterialTheme { PairedDevicesList(state, r.actions(), nowMs = 0) } }
        onNodeWithText("Expires in 1:00").assertIsDisplayed()
        onNodeWithTag(PairingTags.cancelInvite("inv-9")).performClick()
        assertEquals(listOf("cancel:inv-9"), r.events)
    }
}
