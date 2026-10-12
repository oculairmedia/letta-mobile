package com.letta.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-v7aln: rotating while on a chat must not tear the chat down. A rotation recreates
 * the Activity; [StateRestorationTester] replays that (the composition is rebuilt from saved
 * state while ViewModels survive, as they do across a configuration change).
 *
 * The chat's ViewModel lives in its NavBackStackEntry, so "no reload" means: the same entry is
 * on top afterwards, no second chat ViewModel was built (a new one re-resolves and re-hydrates
 * the conversation and reconnects its transport), and the original was never cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatRotationRetentionTest {
    @get:Rule val compose = createComposeRule()

    private class ChatModel : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private val config = LettaConfig(id = "config", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://example.test")
    private val selection = MutableStateFlow<LastChatSelection?>(LastChatSelection("agent-a", "Agent A", "conv-a"))
    private val inputs = AppStartInputs(
        activeConfig = MutableStateFlow(config),
        lastChatSelection = selection,
        favoriteAgentId = MutableStateFlow(null),
        adminAgentId = MutableStateFlow(null),
    )
    private val chatModels = mutableListOf<ChatModel>()
    private lateinit var nav: NavHostController

    @Composable
    private fun AppHost() {
        nav = rememberNavController()
        NavHost(nav, startDestination = rememberAppStartDestination(inputs, initialNotificationTarget = null)) {
            composable<ConversationsRoute> { Text("Conversations") }
            composable<ConfigRoute> { Text("Config") }
            composable<AgentChatRoute> {
                viewModel { ChatModel().also(chatModels::add) }
                Text("Chat")
            }
        }
    }

    private fun assertChatSurvived(entryId: String) {
        assertEquals("the chat's back stack entry must survive", entryId, nav.currentBackStackEntry!!.id)
        assertEquals("rotation must not build a second chat ViewModel", 1, chatModels.size)
        assertFalse("rotation must not clear the chat ViewModel", chatModels.single().cleared)
    }

    @Test
    fun `rotating on the restored last chat keeps its entry and ViewModel`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppHost() }
        compose.waitForIdle()
        val entryId = compose.runOnIdle { nav.currentBackStackEntry!!.id }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.runOnIdle { assertChatSurvived(entryId) }
    }

    @Test
    fun `opening the first chat and then rotating keeps the back stack`() {
        selection.value = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppHost() }
        compose.runOnIdle { nav.navigate(AgentChatRoute(agentId = "agent-a", conversationId = "conv-a")) }
        // The chat records itself as the last selection once it opens.
        selection.value = LastChatSelection("agent-a", "Agent A", "conv-a")
        compose.waitForIdle()
        val entryId = compose.runOnIdle { nav.currentBackStackEntry!!.id }
        compose.runOnIdle { assertChatSurvived(entryId) }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.runOnIdle {
            assertChatSurvived(entryId)
            val screens = nav.currentBackStack.value.filterNot { it.destination is NavGraph }
            assertEquals("the conversations list stays under the chat", 2, screens.size)
        }
    }

    @Test
    fun `clearing all data still lands on config`() {
        val activeConfig = MutableStateFlow<LettaConfig?>(config)
        val clearable = AppStartInputs(activeConfig, selection, MutableStateFlow(null), MutableStateFlow(null))
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = rememberAppStartDestination(clearable, initialNotificationTarget = null)) {
                composable<ConfigRoute> { Text("Config") }
                composable<AgentChatRoute> { Text("Chat") }
            }
        }
        compose.waitForIdle()

        activeConfig.value = null
        selection.value = null
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(nav.currentDestination!!.hasRoute(ConfigRoute::class))
        }
    }

    @Test
    fun `rotating across the expanded width keeps the chat's scroll position`() {
        var expanded = false
        lateinit var listState: LazyListState
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AdaptiveScaffoldLayout(
                expanded = expanded,
                insetPolicy = DestinationInsetPolicy.SelfVertical,
                navigationRail = { Box {} },
            ) {
                AppHostWithTimeline { listState = it }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState.scrollToItem(30) } }
        compose.waitForIdle()

        // Landscape on a phone is wider than 600dp: the rotated Activity lays out expanded.
        expanded = true
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(30, listState.firstVisibleItemIndex) }
    }

    @Composable
    private fun AppHostWithTimeline(onListState: (LazyListState) -> Unit) {
        nav = rememberNavController()
        NavHost(nav, startDestination = rememberAppStartDestination(inputs, initialNotificationTarget = null)) {
            composable<ConversationsRoute> { Text("Conversations") }
            composable<AgentChatRoute> {
                val state = rememberLazyListState()
                onListState(state)
                LazyColumn(state = state) { items(100) { Text("row $it") } }
            }
        }
    }
}
