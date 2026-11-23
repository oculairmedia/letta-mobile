@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.context.AgentContextCardInputs
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.context.ContextMeter
import com.letta.mobile.data.context.limit.ContextLimitScope
import com.letta.mobile.data.context.limit.ContextLimitStops
import com.letta.mobile.ui.chat.surface.composer.TouchComposerActionSheet
import com.letta.mobile.ui.chat.surface.composer.TouchSheetItem
import com.letta.mobile.ui.components.choice.LettaSegment
import com.letta.mobile.ui.components.choice.LettaSegmentedChoice
import com.letta.mobile.ui.components.choice.LettaSegmentedControl
import com.letta.mobile.ui.context.AgentContextPicker
import com.letta.mobile.ui.context.AgentContextSheetContent
import com.letta.mobile.ui.context.ContextLimitControl
import com.letta.mobile.ui.context.ContextLimitSetting
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.modelcontrol.ModelControlUiFixtures
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.chat.surface.timeline.rows.RenderRow
import com.letta.mobile.ui.chat.surface.timeline.rows.message
import com.letta.mobile.ui.chat.surface.timeline.rows.single
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetOverview
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.FleetSummary
import com.letta.mobile.data.home.HomePageReducer
import com.letta.mobile.data.home.HomeStats
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.ui.components.LETTA_PAGE_TITLE_TAG
import com.letta.mobile.ui.shell.header.ShellHeaderDefaults
import com.letta.mobile.ui.shell.header.ShellHeaderTags
import com.letta.mobile.ui.shell.pages.home.HomeGreetingOptions
import com.letta.mobile.ui.shell.pages.home.HomeGreetingTags
import com.letta.mobile.ui.shell.pages.home.HomePage
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageNavigation
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.workspace.ShellMinimalHeader
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeader
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.pages.home.HomeConnectionPanel
import com.letta.mobile.ui.shell.pages.home.HomeConnectionState
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell in the Baken brand (Home rail with the Baken mark, the Home hero with the two-colour mark and the wordmark,
 * a vermilion primary action and a composer) and a short chat (the user's neutral bubble, the assistant sphere),
 * rendered in both themes to build/baken-shell-snapshots for review.
 */
class BakenShellSnapshotTest {
    private val entries = ShellRailMapping.entries(
        ShellRailMapping.groups(listOf("a1" to "Alpha", "b1" to "Beta"), selectedAgentId = null),
        ShellRailFocus(selectedAgentId = "a1"),
    )

    private fun snapshot(dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Row(Modifier.size(FRAME_WIDTH.dp, FRAME_HEIGHT.dp).background(MaterialTheme.colorScheme.background).testTag(FRAME)) {
                    ShellAgentRail(
                        state = ShellAgentRailState(entries = entries, homeSelected = true),
                        actions = ShellAgentRailActions(),
                        modifier = Modifier.fillMaxHeight(),
                    )
                    Column(Modifier.width(CONTENT_WIDTH.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeConnectionPanel(
                            HomeConnectionState(ChatScreenStatus.Loading),
                            onRetryConnection = {},
                            wordmark = DisplayNames.WORDMARK,
                        )
                        Button(onClick = {}) { Text("Send") }
                        OutlinedTextField(value = "Message Alpha", onValueChange = {})
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Home").assertExists()
        val out = File("build/baken-shell-snapshots").apply { mkdirs() }.resolve("shell-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    /** A short chat (the user's neutral bubble, the agent's reply under the vermilion-to-petrol sphere) in one theme. */
    private fun chatSnapshot(dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Column(
                    Modifier.size(CHAT_WIDTH.dp, CHAT_HEIGHT.dp).background(MaterialTheme.colorScheme.background).padding(16.dp)
                        .testTag(FRAME),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RenderRow(single(message("u1", "user", "Plan a taco night for six")))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentSphere(size = 28.dp)
                        Text("Sure: three fillings, two salsas, and a lime crema.", color = MaterialTheme.colorScheme.onSurface)
                    }
                    RenderRow(single(message("u2", "user", "Make one of them vegetarian")))
                }
            }
        }
        waitForIdle()
        val out = File("build/baken-shell-snapshots").apply { mkdirs() }.resolve("chat-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    /** Renders [content] in one theme on the background to build/baken-shell-snapshots/[name]-dark|light.png. */
    private fun render(name: String, dark: Boolean, width: Int, height: Int, content: @Composable ColumnScope.() -> Unit) =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent {
                SharedMaterialTheme(darkTheme = dark) {
                    ProvideLettaHost(LettaHost.Touch) {
                        Column(
                            Modifier.size(width.dp, height.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).testTag(FRAME),
                        ) { content() }
                    }
                }
            }
            waitForIdle()
            val out = File("build/baken-shell-snapshots").apply { mkdirs() }.resolve("$name-${if (dark) "dark" else "light"}.png")
            ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
            assertTrue(out.length() > 0)
        }

    /** The Model and context sheet: the model row, the effort slider, the context total near full, the limit slider, Compact. */
    @Test
    fun modelAndContextSheetInBothThemes() = listOf(true, false).forEach { dark ->
        render("model-and-context", dark, SHEET_WIDTH, SHEET_HEIGHT) {
            val picker = AgentContextPicker(
                state = ModelControlUiFixtures.pickerState(),
                actions = ModelPickerActions(onSelect = {}, onQueryChange = {}, onToggleGroup = {}, onRefresh = {}, onEditModels = null),
                onEffortApplied = { _, _ -> },
            )
            val stops = checkNotNull(ContextLimitStops.of(modelMax = 1_000_000, current = 1_000_000))
            AgentContextSheetContent(
                model = AgentContextCardModel.present(
                    AgentContextCardInputs(
                        "Claude Opus 5.5", "high", ContextMeter.of(859_000, 1_000_000), true, true,
                        compacting = false, turnRunning = false,
                    ),
                ),
                picker = picker,
                onCompact = {},
                limit = ContextLimitControl.Adjustable(
                    ContextLimitSetting(stops, current = 1_000_000, usedTokens = 859_000, scope = ContextLimitScope.Conversation),
                    onApply = {},
                ),
            )
        }
    }

    /** The composer's "Add to message" sheet: neutral tiles, quiet icons. */
    @Test
    fun composerSheetInBothThemes() = listOf(true, false).forEach { dark ->
        render("composer-sheet", dark, SHEET_WIDTH, SHEET_HEIGHT) {
            TouchComposerActionSheet(
                items = listOf(
                    TouchSheetItem("Attach image", LettaIcons.Image, onClick = {}),
                    TouchSheetItem("Take photo", LettaIcons.Camera, onClick = {}),
                    TouchSheetItem("Permission mode", LettaIcons.Add, onClick = {}, stateDescription = "Ask"),
                ),
                onDismiss = {},
            )
        }
    }

    /** The model list with its selected row, and a segmented control (Settings > Runtime). */
    @Test
    fun pickerAndSegmentedInBothThemes() = listOf(true, false).forEach { dark ->
        render("picker-and-segmented", dark, SHEET_WIDTH, SHEET_HEIGHT) {
            LettaSegmentedControl(
                LettaSegmentedChoice(
                    listOf(LettaSegment("local", "Local runtime"), LettaSegment("remote", "Remote host")),
                    selected = "local",
                ),
                onSelect = {},
                modifier = Modifier.padding(16.dp),
            )
            ModelPickerContent(
                ModelControlUiFixtures.pickerState(),
                ModelPickerActions(onSelect = {}, onQueryChange = {}, onToggleGroup = {}, onRefresh = {}, onEditModels = null),
            )
        }
    }

    @Test
    fun shellInBothThemes() = listOf(true, false).forEach(::snapshot)

    /**
     * The phone's Home under its minimal header: the bar's "Baken" starts on the same edge as the page's
     * "Home" and the greeting under it, and the greeting's lineup spans the page.
     */
    @Test
    fun homeAtPhoneWidthLinesTheBarTitleUpWithThePage() = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                ProvideLettaHost(LettaHost.Touch) {
                    Column(Modifier.size(PHONE_WIDTH.dp, PHONE_HEIGHT.dp).background(MaterialTheme.colorScheme.background).testTag(FRAME)) {
                        // Bounded to the phone app bar, as every host bounds it: its free width fills its height.
                        ShellWorkspaceHeader(
                            ShellWorkspaceHeaderState(minimal = ShellMinimalHeader(identity = null, onOpenNavigation = {})),
                            Modifier.height(ShellHeaderDefaults.TouchMinHeight),
                        )
                        HomePage(
                            modifier = Modifier.weight(1f),
                            state = threeAgentHome(),
                            callbacks = HomePageCallbacks(
                                InertSnapshotHome,
                                HomePageNavigation(onSubmitPrompt = {}, onOpenConversation = {}, onOpenAgent = {}, onOpenShortcut = {}),
                            ),
                            options = HomePageOptions(showSearch = false, touch = true, greeting = HomeGreetingOptions(hour = AFTERNOON)),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val bar = onNodeWithTag(ShellHeaderTags.TITLE).getUnclippedBoundsInRoot().left
        val page = onNodeWithTag(LETTA_PAGE_TITLE_TAG).getUnclippedBoundsInRoot().left
        val greeting = onNodeWithTag(HomeGreetingTags.LINE).getUnclippedBoundsInRoot().left
        assertEquals(page.value, bar.value, EDGE_TOLERANCE, "bar title at $bar, page title at $page")
        assertEquals(page.value, greeting.value, EDGE_TOLERANCE, "greeting at $greeting, page title at $page")
        val out = File("build/baken-shell-snapshots").apply { mkdirs() }.resolve("home-phone-dark.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun chatInBothThemes() = listOf(true, false).forEach(::chatSnapshot)

    private companion object {
        const val FRAME = "baken-shell-frame"
        const val WINDOW_WIDTH = 900
        const val WINDOW_HEIGHT = 700
        const val FRAME_WIDTH = 760
        const val FRAME_HEIGHT = 600
        const val CONTENT_WIDTH = 680
        const val SHEET_WIDTH = 420
        const val SHEET_HEIGHT = 660
        const val CHAT_WIDTH = 520
        const val CHAT_HEIGHT = 320
        const val PHONE_WIDTH = 411
        const val PHONE_HEIGHT = 640
        const val AFTERNOON = 15
        const val EDGE_TOLERANCE = 0.5f
    }

    /** Home with three agents: fewer than a phone's stage holds, so the lineup has width to share. */
    private fun threeAgentHome(): HomePageState {
        val agents = listOf("Scout", "Planner", "Archivist").mapIndexed { index, name ->
            FleetAgentStat(
                agentId = "a-$index", name = name, model = "openai/gpt-5", conversationCount = 3,
                lastActivity = null, running = false, activityByDay = emptyList(),
            )
        }
        val fleet = FleetOverview(summary = FleetSummary(totalAgents = agents.size), agents = agents, recent = emptyList())
        return HomePageReducer.withStats(HomePageReducer.withFleet(HomePageState(), fleet), HomeStats(loading = false))
    }

    private object InertSnapshotHome : HomePageActions {
        override fun refresh() = Unit
        override fun updateSearchQuery(query: String) = Unit
        override fun clearSearch() = Unit
        override fun selectSort(key: FleetSortKey) = Unit
        override fun reorderPins(keys: List<String>) = Unit
        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit
        override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    }
}
