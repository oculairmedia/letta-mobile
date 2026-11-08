@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.initialLiveDesktopChatSurfaceState
import com.letta.mobile.data.shell.ShellLink
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopDeepLinkTest {
    @Test
    fun parsesSupportedMeridianDestinations() {
        assertEquals(ShellLink.Open(ShellDestination.Chat()), parseDesktopDeepLink(URI("meridian://conversations")))
        assertEquals(ShellLink.Open(ShellDestination.Settings()), parseDesktopDeepLink(URI("meridian://settings")))
        assertEquals(
            ShellLink.Open(ShellDestination.Chat(conversationId = "conversation-42")),
            parseDesktopDeepLink(URI("meridian://conversation/conversation-42")),
        )
        assertEquals(
            ShellLink.Open(ShellDestination.Chat(agentId = "agent-7")),
            parseDesktopDeepLink(URI("meridian://agent/agent-7")),
        )
        // letta-mobile-ckmub.3.4: every shared destination has a link, e.g. the Tools anchor.
        assertEquals(
            ShellLink.Open(ShellDestination.Skills(section = ShellSkillsSection.Tools)),
            parseDesktopDeepLink(URI("meridian://skills?section=tools")),
        )
    }

    @Test
    fun rejectsForeignOrIncompleteLinks() {
        assertNull(parseDesktopDeepLink(URI("https://settings")))
        assertNull(parseDesktopDeepLink(URI("meridian://conversation")))
        assertNull(parseDesktopDeepLink(URI("meridian://unknown/value")))
        // Opaque form: no host/path, must be ignored rather than crash.
        assertNull(parseDesktopDeepLink(URI("meridian:settings")))
        assertNull(parseDesktopDeepLink(URI("meridian:conversation/conversation-42")))
    }

    // letta-mobile-dpv7m.3: a scanned pairing link opens the desktop's pairing card.
    @Test
    fun pairLinksRouteToPairingAndNeverPrintTheSecret() {
        val link = "meridian://pair?qr=letta-qr-v1.eyJ2IjoxfQ&dial=203.0.113.7%3A4501"
        assertEquals(ShellLink.Pair(link), parseDesktopDeepLink(URI(link)))
        assertNull(parseDesktopDeepLink(URI("meridian://pair?qr=not-an-envelope")))
        val request = DesktopDeepLinkRequest(1, URI(link))
        assertFalse("letta-qr-v1" in request.toString())
        assertFalse("letta-qr-v1" in ShellLink.Pair(link).toString())
    }

    @Test
    fun recreatingTheRoutingEffectDoesNotReplayAConsumedLink() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        val requests = MutableStateFlow<DesktopDeepLinkRequest?>(DesktopDeepLinkRequest(1, URI("meridian://memory")))
        var opens = 0
        val handlers = DesktopDeepLinkHandlers(
            onDestinationSelected = { opens++ },
            onConversationSelected = {},
            onAgentSelected = {},
        )
        setContent { if (mounted) DesktopDeepLinkEffect(requests, handlers) }
        waitForIdle()
        runOnIdle { mounted = false }
        waitForIdle()
        runOnIdle { mounted = true }
        waitForIdle()
        runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun conversationLinkIsOneNavigationEntry() {
        val destinations = mutableListOf<ShellDestination>()
        val oldCallbacks = mutableListOf<String>()
        val handlers = DesktopDeepLinkHandlers(
            onDestinationSelected = { destinations.add(it) },
            onConversationSelected = { oldCallbacks.add(it) },
            onAgentSelected = { oldCallbacks.add(it) },
        )
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://conversation/missing")), handlers)
        assertEquals(listOf<ShellDestination>(ShellDestination.Chat(conversationId = "missing")), destinations)
        assertEquals(emptyList<String>(), oldCallbacks)
    }

    @Test
    fun newestUnlistedLinkCannotBeReplacedByAnOlderCatalogRow() {
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val handlers = DesktopDeepLinkHandlers(workspace::navigate, {}, {})
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://conversation/older")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://conversation/missing")), handlers)
        val oldRow = DesktopConversationSummary(
            id = "older", title = "Old", agentName = "A", updatedAtLabel = "2026-01-01T00:00:00Z",
            lastMessagePreview = "", agentId = "agent-a",
        )
        val chat = initialLiveDesktopChatSurfaceState(defaultDesktopBootstrapState()).copy(
            conversations = listOf(oldRow), connectionState = DesktopChatConnectionState.Live, isLoading = false,
        )
        val selected = mutableListOf<ConversationId>()

        projectDesktopFocus(workspace, chat) { selected.add(it) }

        assertEquals(ConversationId("missing"), workspace.currentFocus.conversationId)
        assertEquals(listOf(ConversationId("missing")), selected)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-b")))
        projectDesktopFocus(workspace, chat) { selected.add(it) }
        assertEquals(AgentId("agent-b"), workspace.currentFocus.agentId)
        assertNull(workspace.currentFocus.conversationId)
        assertEquals(listOf(ConversationId("missing")), selected)
    }

    @Test
    fun ownerSwitchWhileOldChatLoadsOpensNewTarget() {
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-b"), ConversationId("new-chat"),
            com.letta.mobile.data.shell.CurrentFocusChatMode.Exact))
        val chat = initialLiveDesktopChatSurfaceState(defaultDesktopBootstrapState()).copy(
            conversations = listOf(DesktopConversationSummary("new-chat", "New", "B", "2026-01-01T00:00:00Z", "", agentId = "agent-b")),
            selectedConversationId = "old-chat", connectionState = DesktopChatConnectionState.Loading, isLoading = true,
        )
        val selected = mutableListOf<ConversationId>()
        projectDesktopFocus(workspace, chat) { selected.add(it) }
        assertEquals(listOf(ConversationId("new-chat")), selected)
        assertEquals(ConversationId("new-chat"), workspace.currentFocus.conversationId)
    }

    @Test
    fun handlersRouteEachLinkKind() {
        val calls = mutableListOf<String>()
        val handlers = DesktopDeepLinkHandlers(
            onDestinationSelected = { calls += "destination:$it" },
            onConversationSelected = { calls += "conversation:$it" },
            onAgentSelected = { calls += "agent:$it" },
            onPairLink = { calls += "pair" },
        )
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://conversation/c1")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://agent/a1")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://pair?qr=letta-qr-v1.eyJ2IjoxfQ")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://memory")), handlers)
        handleDesktopDeepLink(null, handlers)
        assertEquals(
            listOf(
                "destination:${ShellDestination.Chat(conversationId = "c1")}",
                "destination:${ShellDestination.Chat(agentId = "a1")}",
                "pair",
                "destination:${ShellDestination.Settings(ShellSettingsSection.Pairing)}",
                "destination:${ShellDestination.Memory()}",
            ),
            calls,
        )
    }
}
