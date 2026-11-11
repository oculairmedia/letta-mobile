@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.client.SecretClientOutcome
import com.letta.mobile.data.secrets.client.SecretInput
import com.letta.mobile.data.secrets.client.SecretRequestClient
import com.letta.mobile.data.secrets.client.SecretRequestScope
import com.letta.mobile.data.secrets.request.AuthoredPurpose
import com.letta.mobile.data.secrets.request.SecretKeystoreName
import com.letta.mobile.data.secrets.request.SecretPurpose
import com.letta.mobile.data.secrets.request.SecretRequestDetails
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import com.letta.mobile.data.secrets.request.SecretRequestTarget
import com.letta.mobile.data.secrets.request.SecretRequestTiming
import com.letta.mobile.data.secrets.request.TextAuthor
import com.letta.mobile.data.secrets.request.VaultFieldName
import com.letta.mobile.data.secrets.request.VaultItemName
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.secrets.ProvideSecretRequestHost
import com.letta.mobile.ui.secrets.SecretRequestCardTags
import com.letta.mobile.ui.secrets.SecretRequestFormTags
import com.letta.mobile.ui.secrets.SecretRequestHost
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val SecretPage = "secret-request-page"
private const val SecretHostPane = "secret-request-host-pane"
private val SecretHostPaneWidth = 100.dp
private val SecretWindowHeight = 800.dp
private val SecretNow = Instant.fromEpochMilliseconds(1_800_000_000_000)

/**
 * The shell mounts the pending secret request card itself, above the main lane, at every width a
 * window can have (360, 700, 760 and 1200 dp): the card fits the lane, the page sits under it, and
 * a layout that names the card (a hand-edited config) neither draws a second one nor shows it while
 * nothing is pending. The secure form it opens fits each width with its controls in view.
 */
class ShellWorkspaceSecretRequestTest {
    private val request = SecretRequestRecord(
        requestId = SecretRequestId(SecretRequestId.PREFIX + "1".padStart(43, 'A')),
        details = SecretRequestDetails(
            AgentId("agent-a"),
            SecretRequestTarget(SecretKeystoreName("test.vault"), VaultItemName("shop/gelato"), VaultFieldName("api_key")),
            AuthoredPurpose(SecretPurpose("Sync the print catalogue"), TextAuthor.AGENT),
        ),
        timing = SecretRequestTiming(SecretNow.toEpochMilliseconds(), SecretNow.toEpochMilliseconds() + SecretRequestLimits.DEFAULT_TTL_MS),
        status = SecretRequestStatus(SecretRequestState.PENDING),
    )

    /** Lists [requests]; never answers (these tests only lay the views out). */
    private class ListingClient(requests: List<SecretRequestRecord>) : SecretRequestClient {
        val listed = MutableStateFlow(requests)

        override fun pending(scope: SecretRequestScope): Flow<List<SecretRequestRecord>> = listed

        override suspend fun submit(requestId: SecretRequestId, value: SecretInput): SecretClientOutcome {
            value.wipe()
            return SecretClientOutcome.Unreachable
        }

        override suspend fun decline(requestId: SecretRequestId): SecretClientOutcome = SecretClientOutcome.Unreachable
    }

    private fun state(lanes: ShellLayoutConfig) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
        ),
        layout = ShellWorkspaceLayout(lanes = lanes),
    )

    private val shell = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(
            main = { modifier -> Box(modifier.testTag(SecretPage)) },
            agentCard = {},
            sidePane = { Box(Modifier.width(SecretHostPaneWidth).fillMaxHeight().testTag(SecretHostPane)) },
            overlays = {},
        ),
        chrome = ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer(),
        events = ShellWorkspaceEvents(),
    )

    private val fixedClock = object : Clock {
        override fun now(): Instant = SecretNow
    }

    /** A config that names the card in the main and side lanes, as only a hand edit could. */
    private val tampered = ShellLayoutConfig(
        version = ShellLayoutConfig.VERSION,
        lanes = mapOf(
            ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.SECRET_REQUEST), ModuleSlot(ShellViews.PAGE)),
            ShellLanes.SIDE to listOf(ModuleSlot(ShellViews.SECRET_REQUEST), ModuleSlot(ShellViews.SIDE_PANE)),
        ),
    )

    private fun window(width: Dp, client: ListingClient, config: ShellLayoutConfig = ShellLayoutConfig.Default, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width.value.toInt(), height = SecretWindowHeight.value.toInt()) {
            val host = SecretRequestHost(client, agentName = { "Ada" }, clock = fixedClock)
            setContent {
                ProvideSecretRequestHost(host) {
                    LettaShellWorkspace(state(config), shell, Modifier.size(width = width, height = SecretWindowHeight))
                }
            }
            waitForIdle()
            block()
        }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun cardSitsAboveThePage(width: Dp) = window(width, ListingClient(listOf(request)), tampered) {
        assertEquals(1, count(SecretRequestCardTags.CARD), "one card, however the layout names it, at $width")
        val card = bounds(SecretRequestCardTags.CARD)
        val page = bounds(SecretPage)
        assertTrue(card.left >= 0.dp && card.right <= width, "the card fits the window at $width: $card")
        assertTrue(card.bottom <= page.top, "the page sits under the card at $width")
        assertEquals(SecretWindowHeight.value, page.bottom.value, absoluteTolerance = 1f, message = "the page runs to the bottom at $width")
        onNodeWithTag(SecretRequestCardTags.OPEN).assertIsDisplayed()
    }

    @Test
    fun theCardSitsAboveThePageOnAPhone() = cardSitsAboveThePage(360.dp)

    @Test
    fun theCardSitsAboveThePageJustBelowTheWideBreakpoint() = cardSitsAboveThePage(700.dp)

    @Test
    fun theCardSitsAboveThePageJustAboveTheWideBreakpoint() = cardSitsAboveThePage(760.dp)

    @Test
    fun theCardSitsAboveThePageOnADesktop() = cardSitsAboveThePage(1200.dp)

    @Test
    fun nothingIsMountedWhileNoRequestIsPendingEvenIfTheLayoutNamesTheCard() = window(1200.dp, ListingClient(emptyList()), tampered) {
        assertEquals(0, count(SecretRequestCardTags.CARD))
        assertEquals(0f, bounds(SecretPage).top.value, absoluteTolerance = 1f, message = "the page starts at the top")
    }

    private fun theFormFits(width: Dp) = window(width, ListingClient(listOf(request))) {
        onNodeWithTag(SecretRequestCardTags.OPEN).performClick()
        waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestFormTags.FIELD) == 1 }
        listOf(SecretRequestFormTags.FIELD, SecretRequestFormTags.REVEAL, SecretRequestFormTags.DECLINE, SecretRequestFormTags.SUBMIT)
            .forEach { tag ->
                onNodeWithTag(tag).assertIsDisplayed()
                val box = bounds(tag)
                assertTrue(box.left >= 0.dp && box.right <= width, "$tag fits the window at $width: $box")
            }
    }

    @Test
    fun theFormFitsAPhone() = theFormFits(360.dp)

    @Test
    fun theFormFitsJustBelowTheWideBreakpoint() = theFormFits(700.dp)

    @Test
    fun theFormFitsJustAboveTheWideBreakpoint() = theFormFits(760.dp)

    @Test
    fun theFormFitsADesktop() = theFormFits(1200.dp)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
