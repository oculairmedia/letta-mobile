package com.letta.mobile.ui.shell

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.header.ShellHeaderDefaults
import com.letta.mobile.ui.shell.header.ShellHeaderTags
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.rememberShellSearchSlot
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageNavigation
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.pages.home.HomePageTags
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android root and pushed pages at phone size, framed as the Android screens frame them
 * (letta-mobile-kg4vy): the shared [ShellHeader] in [LettaPageScaffold]'s header slot on a touch host.
 * The header must sit at the top, right under the status bar, at its app-bar height, and the page
 * must fill the rest of the window. The regression: the header's free-width lane filled the slot's
 * whole height, so the header took the full page, centred its row mid-screen and left the content
 * zero height.
 *
 * Robolectric reports no system bars, so the phone's are dispatched as an edge-to-edge window gets them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@Tag("integration")
class ShellHeaderPageLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeHeaderSitsAtTheTopAndTheHomePageFillsTheRest() {
        show(content = { modifier -> HomeContent(modifier) }) {
            // As HomeScreen: menu, title with the backend label, the Home search and Settings.
            ShellHeader(
                title = HeaderTitle.Text("Admin", "Iroh · 100.93.254.12:4501", onSubtitleClick = {}),
                search = rememberShellSearchSlot(home = NoopHome, onHome = true, sources = UnifiedSearchSources(), onOpen = {}),
                controls = HeaderControls(
                    nav = HeaderNav.Menu(onClick = {}),
                    actions = listOf(HeaderAction.Icon(LettaIcons.Settings, "Settings") {}),
                ),
            )
        }
        assertHeaderAtTopAndContentFills(contentTag = HomePageTags.PAGE)
        assertTrue("the Home composer is laid out", composeRule.height(HomePageTags.COMPOSER) > 0.dp)
    }

    @Test
    fun channelsHeaderSitsAtTheTopAndThePageFillsTheRest() = assertPushedPage("Channels")

    @Test
    fun memoryHeaderSitsAtTheTopAndThePageFillsTheRest() = assertPushedPage("Memory")

    @Test
    fun skillsHeaderSitsAtTheTopAndThePageFillsTheRest() = assertPushedPage("Skills")

    /** Channels, Memory and Skills: a back arrow and a title, as their screens draw them. */
    private fun assertPushedPage(title: String) {
        show(content = { modifier -> Box(modifier.fillMaxSize().testTag(CONTENT)) }) {
            ShellHeader(
                title = HeaderTitle.Text(title),
                controls = HeaderControls(nav = HeaderNav.Back(onClick = {})),
            )
        }
        assertHeaderAtTopAndContentFills(contentTag = CONTENT)
    }

    private fun show(content: @Composable (Modifier) -> Unit, header: @Composable () -> Unit) {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            MaterialTheme {
                LettaPageScaffold(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    header = header,
                ) { padding -> content(Modifier.padding(padding)) }
            }
        }
        composeRule.runOnIdle { dispatchPhoneInsets(view) }
        composeRule.waitForIdle()
        density = view.resources.displayMetrics.density
    }

    @Composable
    private fun HomeContent(modifier: Modifier) {
        ShellPage(
            destination = ShellDestination.Home,
            deps = ShellPageDeps(
                home = shellHomePage(
                    state = HomePageState(),
                    callbacks = HomePageCallbacks(NoopHome, NoopNavigation),
                    options = HomePageOptions(showTitle = false, showSearch = false, touch = true),
                ),
            ),
            modifier = modifier,
        )
    }

    private var density = 1f

    private fun assertHeaderAtTopAndContentFills(contentTag: String) {
        val status = (STATUS_BAR_PX / density).dp
        val nav = (NAV_BAR_PX / density).dp
        val window = composeRule.onRoot().getBoundsInRoot().bottom
        val headerHeight = ShellHeaderDefaults.TouchMinHeight

        assertDpEquals(status, composeRule.top(ShellHeaderTags.HEADER), "header starts right under the status bar")
        assertDpEquals(headerHeight, composeRule.height(ShellHeaderTags.HEADER), "header keeps its app-bar height")
        assertDpEquals(status + headerHeight, composeRule.top(contentTag), "content starts under the header")
        assertDpEquals(window - nav, composeRule.bottom(contentTag), "content fills down to the navigation bar")
    }

    private fun dispatchPhoneInsets(view: View) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR_PX))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun ComposeContentTestRule.top(tag: String): Dp = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().top

    private fun ComposeContentTestRule.bottom(tag: String): Dp = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().bottom

    private fun ComposeContentTestRule.height(tag: String): Dp =
        onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let { it.bottom - it.top }

    private fun assertDpEquals(expected: Dp, actual: Dp, message: String) =
        assertEquals(message, expected.value, actual.value, 1f)

    private object NoopHome : HomePageActions {
        override fun refresh() = Unit
        override fun updateSearchQuery(query: String) = Unit
        override fun clearSearch() = Unit
        override fun selectSort(key: FleetSortKey) = Unit
        override fun reorderPins(keys: List<String>) = Unit
        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit
        override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    }

    private companion object {
        /** A phone's status bar (24dp) and gesture bar (16dp) at xxhdpi. */
        const val STATUS_BAR_PX = 72
        const val NAV_BAR_PX = 48
        const val CONTENT = "content"

        val NoopNavigation = HomePageNavigation(
            onSubmitPrompt = {},
            onOpenConversation = {},
            onOpenAgent = {},
            onOpenShortcut = {},
        )
    }
}
