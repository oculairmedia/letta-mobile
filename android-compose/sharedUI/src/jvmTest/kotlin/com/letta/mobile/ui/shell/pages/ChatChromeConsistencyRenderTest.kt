@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetOverview
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.FleetSummary
import com.letta.mobile.data.home.HomePageReducer
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeStats
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.composer.ChatComposerPanel
import com.letta.mobile.ui.chat.surface.composer.ComposerInputs
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.host.LocalHostTitlesPage
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.pages.home.HomePage
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.pages.home.HomePageTags
import com.letta.mobile.ui.shell.pages.home.InertHomeCallbacks
import com.letta.mobile.ui.shell.pages.home.withSharedPromptBar
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Home and the chat composer side by side at the review widths (360, 700, 760, 1200 dp) in Dark and
 * Light, rendered to build/chat-chrome-render/ for review: below the wide breakpoint (the compact
 * window, where the title bar names the page) Home is untitled and both draw the same Touch bar
 * and Send; at 1200 dp both draw the pointer card. Each frame asserts the shared Send is there.
 */
class ChatChromeConsistencyRenderTest {
    @Test
    fun homeAndChatShareTheComposerAtEveryReviewWidth() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) {
                render("home-$width-${theme(dark)}.png", width, HOME_HEIGHT, dark) { HomeFrame(width) }
                render("chat-$width-${theme(dark)}.png", width, COMPOSER_HEIGHT, dark) { ComposerFrame() }
            }
        }
    }

    @Composable
    private fun HomeFrame(width: Int) {
        CompositionLocalProvider(LocalHostTitlesPage provides compact(width)) {
            HomePage(state = state(), callbacks = InertHomeCallbacks, options = HomePageOptions(composerPlaceholder = "Message Letta…").withSharedPromptBar())
        }
    }

    @Composable
    private fun ComposerFrame() {
        ChatComposerPanel(ComposerInputs(composer = ChatComposerUiState(text = "Plan the trip", canSend = true), actions = RecordingChatActions()))
    }

    private fun render(name: String, width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                SharedMaterialTheme(darkTheme = dark) {
                    ProvideLettaHost(if (compact(width)) LettaHost.Touch else LettaHost.Pointer) {
                        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { content() } }
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(ComposerTestTags.SEND).assertExists()
            if (name.startsWith("home")) onNodeWithTag(HomePageTags.COMPOSER).assertExists()
            val file = File("build/chat-chrome-render").apply { mkdirs() }.resolve(name)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        }

    private fun compact(width: Int) = width < LettaDimens.Pane.wideBreakpoint.value

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private fun state(): HomePageState {
        val now = Clock.System.now()
        val agents = listOf("Scout", "Planner", "Archivist").mapIndexed { index, name ->
            FleetAgentStat(agentId = "a-$index", name = name, model = "openai/gpt-5", conversationCount = 6 - index, lastActivity = now, running = false, activityByDay = List(14) { 0 })
        }
        val recent = listOf("Trip planning" to 7.minutes, "Garden layout" to 30.minutes, "Reading list" to 3.hours).mapIndexed { index, (title, age) ->
            val agent = agents[index]
            FleetRecentConversation("c-$index", agent.agentId, agent.name, title, "", now - age, (now - age).toString())
        }
        val fleet = FleetOverview(summary = FleetSummary(totalAgents = 3, totalConversations = 15), agents = agents, recent = recent)
        return HomePageReducer.withStats(HomePageReducer.withFleet(HomePageState(), fleet), HomeStats(loading = false))
    }

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HOME_HEIGHT = 900
        const val COMPOSER_HEIGHT = 200
    }
}
