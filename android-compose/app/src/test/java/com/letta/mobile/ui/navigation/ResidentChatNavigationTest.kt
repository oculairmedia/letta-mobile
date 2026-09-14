package com.letta.mobile.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.feature.chat.route.AgentChatRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ResidentChatNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun returningFromListReusesExactChatEntryButDifferentConversationDoesNot() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "list") {
                composable("list") { Text("Conversations") }
                composable<AgentChatRoute> { Text("Chat") }
            }
        }
        val route = AgentChatRoute(agentId = "agent-a", conversationId = "conv-a")
        lateinit var original: String
        compose.runOnIdle {
            nav.openResidentChatOrNavigate(route)
            original = nav.currentBackStackEntry!!.id
            nav.navigate("list")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            nav.openResidentChatOrNavigate(route)
            assertEquals("Reopening must preserve the ViewModel-owning entry", original, nav.currentBackStackEntry!!.id)
            nav.navigate("list")
            nav.openResidentChatOrNavigate(route.copy(conversationId = "conv-b"))
            assertNotEquals(original, nav.currentBackStackEntry!!.id)
        }
    }
}
