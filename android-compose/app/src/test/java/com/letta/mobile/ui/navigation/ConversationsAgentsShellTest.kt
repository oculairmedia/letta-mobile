package com.letta.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.letta.mobile.AgentListLaunchTarget
import com.letta.mobile.ShareLaunchTarget
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.screens.agentlist.AgentListChrome
import com.letta.mobile.ui.screens.agentlist.AgentListRouteContent
import com.letta.mobile.ui.screens.conversations.ConversationsChrome
import com.letta.mobile.ui.screens.conversations.ConversationsRouteContent
import com.letta.mobile.ui.screens.conversations.ConversationsRoutePage
import com.letta.mobile.ui.shell.pages.agents.AgentsPageState
import com.letta.mobile.ui.shell.pages.agents.AgentsPageTags
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageActions
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageInput
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageMapping
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageTags
import com.letta.mobile.ui.shell.rail.ShellAgentLibraryTags
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.scaffold.ShellListDetailTags
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.test.setLettaTestContent
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): the Chat tab's conversations and the agent list are the
 * shared shell's pages on Android. At phone size the conversations route is the shared roster alone;
 * at tablet width the shell's wide layout docks it beside the chat. The agent list is the shell's
 * agent library. Deep links (share, the agent-list automation extra, a chat destination without an
 * agent) land on those pages, and the roster keeps its place across a rotation over the breakpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = PHONE)
@Tag("integration")
class ConversationsAgentsShellTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun page(conversations: List<ChatConversationSummary> = SAMPLE) = ConversationsRoutePage(
        state = ConversationsPageMapping.state(
            ConversationsPageInput(conversations = conversations, pinnedIds = setOf("conv-pinned")),
            NOW,
        ),
        actions = ConversationsPageActions(conversations = ShellAgentPanelActions(onConversationSelected = { opened += it })),
        chrome = ConversationsChrome(backendLabel = "Iroh"),
    )

    @Test
    fun phoneConversationsAreTheSharedRosterAlone() {
        compose.setLettaTestContent {
            ConversationsRouteContent(page(), detail = { Text("chat") })
        }
        compose.onNodeWithTag(ConversationsPageTags.PAGE).assertIsDisplayed()
        compose.onNodeWithTag(LIST.header("pinned"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LIST.header("agent-agent-a"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LIST.header("agent-agent-b"), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTagExists(ShellListDetailTags.LIST))
        assertFalse("a phone shows no detail pane", compose.onAllNodesWithTagExists(ShellListDetailTags.DETAIL))

        compose.onNodeWithTag(LIST.row("agent-agent-a:conv-a1").value, useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(listOf("conv-a1"), opened) }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletConversationsDockBesideTheChat() {
        compose.setLettaTestContent {
            ConversationsRouteContent(page(), detail = { Text("chat", Modifier.testTag(DETAIL_CONTENT)) })
        }
        compose.onNodeWithTag(DETAIL_CONTENT).assertIsDisplayed()
        compose.onNodeWithTag(LIST.row("agent-agent-a:conv-a1").value, useUnmergedTree = true).assertIsDisplayed()
        val list = compose.onNodeWithTag(ShellListDetailTags.LIST).getBoundsInRoot()
        assertEquals(LettaDimens.Pane.sidePanelWidth.value, list.width.value, 1f)
    }

    @Test
    fun theConversationRosterKeepsItsScrollAcrossARotationOverTheBreakpoint() {
        var wide by mutableStateOf(false)
        val many = (0 until 60).map { summary("conv-$it", "agent-a", "Agent A", "Chat $it") }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            androidx.compose.material3.MaterialTheme {
                Box(Modifier.requiredWidth(if (wide) 1000.dp else 400.dp)) { ConversationsRouteContent(page(many), detail = {}) }
            }
        }
        compose.onNodeWithTag(LIST.value).performScrollToIndex(40)
        compose.waitForIdle()

        wide = true
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag(ShellListDetailTags.DETAIL).assertExists()
        compose.onNodeWithText("Chat 0").assertDoesNotExist()
    }

    @Test
    fun agentListIsTheSharedAgentLibrary() {
        compose.setLettaTestContent {
            AgentListRouteContent(AgentsPageState(agents = AGENTS), ShellAgentRailActions(), AgentListChrome())
        }
        compose.onNodeWithTag(AgentsPageTags.PAGE).assertIsDisplayed()
        compose.onNodeWithTag(ShellAgentLibraryTags.LIST).assertIsDisplayed()
        compose.onAllNodesWithText("Builder").onFirst().assertIsDisplayed()
        assertFalse(compose.onAllNodesWithTagExists(AgentsPageTags.SHARE_PREVIEW))
    }

    @Test
    fun shareDeepLinkOpensTheAgentLibraryWithThePreview() {
        val route = resolveStartDestination(
            hasConfig = true,
            initialNotificationTarget = ShareLaunchTarget(sharedText = "look at this"),
            restoredChatSelection = null,
            fallbackAgentId = null,
        )
        assertEquals(ShareToAgentRoute("look at this"), route)
        lateinit var nav: NavHostController
        compose.setLettaTestContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = route) {
                composable<ShareToAgentRoute> { entry ->
                    val share = entry.toRoute<ShareToAgentRoute>()
                    AgentListRouteContent(AgentsPageState(agents = AGENTS, sharePreview = share.sharedText), ShellAgentRailActions(), AgentListChrome())
                }
            }
        }
        compose.onNodeWithTag(AgentsPageTags.SHARE_PREVIEW).assertIsDisplayed()
        compose.onNodeWithText("look at this").assertIsDisplayed()
    }

    @Test
    fun agentListAndChatWithoutAgentDeepLinksLandOnTheSharedPages() {
        assertEquals(AgentListRoute, resolveStartDestination(true, AgentListLaunchTarget, null, null))
        lateinit var nav: NavHostController
        compose.setLettaTestContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = AgentListRoute) {
                composable<AgentListRoute> { AgentListRouteContent(AgentsPageState(agents = AGENTS), ShellAgentRailActions(), AgentListChrome()) }
                composable<ConversationsRoute> { ConversationsRouteContent(page()) }
            }
        }
        compose.onNodeWithTag(AgentsPageTags.PAGE).assertIsDisplayed()

        compose.runOnIdle { nav.navigateTo(ShellDestination.Chat()) }
        compose.waitForIdle()

        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute(ConversationsRoute::class)) }
        compose.onNodeWithTag(ConversationsPageTags.PAGE).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T12:00:00Z")
        val LIST = RosterListTag(ConversationsPageTags.LIST)
        const val DETAIL_CONTENT = "detail-content"
        val AGENTS = listOf("agent-a" to "Builder", "agent-b" to "Researcher")

        fun summary(id: String, agentId: String, agentName: String, title: String) = ChatConversationSummary(
            id = id,
            title = title,
            agentName = agentName,
            updatedAtLabel = "2026-10-12T11:00:00Z",
            lastMessagePreview = "",
            agentId = agentId,
        )

        val SAMPLE = listOf(
            summary("conv-pinned", "agent-b", "Agent B", "Pinned chat"),
            summary("conv-a1", "agent-a", "Agent A", "Release triage"),
            summary("conv-b1", "agent-b", "Agent B", "Research digest"),
        )
    }
}

private const val PHONE = "w411dp-h891dp-xxhdpi"
private const val TABLET = "w1280dp-h800dp-xhdpi"
