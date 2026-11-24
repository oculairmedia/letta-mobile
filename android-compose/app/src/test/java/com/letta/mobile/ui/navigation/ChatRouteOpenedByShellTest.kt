package com.letta.mobile.ui.navigation

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.feature.chat.route.AgentChatRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties

/**
 * Pins what a chat opened by the shell's verbs carries: the agent, the conversation, the freshness
 * nonce and the first message, and no project. The route is the page's only input, so a route built
 * without project metadata cannot open a project chat.
 */
@Tag("unit")
class ChatRouteOpenedByShellTest {
    private val agent = AgentId("agent-1")
    private val conversation = ConversationId("c-1")
    private val fleet = listOf(Conversation(id = conversation, agentId = agent))
    private val workspace = ShellWorkspaceState(ShellDestination.Home)
    private val port = AndroidShellChatPort(workspace, { fleet }, { listOf(agent) }, clock = { 7L })

    @Test
    fun `a selected conversation opens a route with no project`() {
        port.selectConversation(conversation)

        assertNoProject(openedRoute())
        assertEquals(agent.value, openedRoute().agentId)
        assertEquals(conversation.value, openedRoute().conversationId)
        assertNull(openedRoute().freshRouteKey)
    }

    @Test
    fun `a new chat is fresh and carries no project either`() {
        port.startNewChat(agent)

        val route = openedRoute()
        assertNoProject(route)
        assertNull("a new chat opens no conversation", route.conversationId)
        assertEquals(7L, route.freshRouteKey)
    }

    @Test
    fun `a prompt sent into a conversation travels as the route's first message`() {
        port.send(conversation, "hello")

        val route = openedRoute()
        assertNoProject(route)
        assertEquals("hello", route.initialMessage)
        assertEquals(conversation.value, route.conversationId)
    }

    /**
     * The projection over the opening the shell's verb produced. It applies the same agent,
     * conversation, freshness and first-message arguments the page's route does, so a project that
     * reaches the page must reach this list first.
     */
    private fun openedRoute(): AgentChatRoute {
        val opening = requireNotNull(port.opening) { "the verb opened nothing" }
        return AgentChatRoute(
            agentId = opening.agent.value,
            conversationId = opening.conversation?.value,
            freshRouteKey = opening.fresh,
            initialMessage = opening.firstMessage,
        )
    }

    private fun assertNoProject(route: AgentChatRoute) {
        val carried = projectFields.mapNotNull { field -> field.get(route)?.let { field.name to it } }
        assertTrue("the shell's route carries project metadata: $carried", carried.isEmpty())
    }

    private companion object {
        private val projectFields = AgentChatRoute::class.memberProperties.filter {
            it.visibility == KVisibility.PUBLIC && it.name.startsWith("project")
        }
    }
}
