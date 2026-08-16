@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.desktopshell.ShellLayoutState
import kotlin.test.Test

private const val SidebarContentTag = "sidebar-content"
private const val ChatComposerTag = "chat-composer"

/**
 * Wires [ShellLayoutReducer] + [DesktopCollapsibleSidebar] +
 * [DesktopSidebarToggleButton] together the same way [LettaDesktopApp] does
 * (BoxWithConstraints width feeds the reducer; the toggle and container both
 * read the resulting state), so these tests exercise the production
 * layout/visibility contract rather than only the pure reducer (already
 * covered in sharedLogic commonTest).
 *
 * The composer + scroll state are deliberately siblings of the collapsible
 * sidebar — not nested inside it — mirroring how [ChatDetailPane] sits
 * beside (not inside) [DesktopAgentSidebar] in the real shell. That
 * placement is what makes AC #4 (collapsing preserves composer draft +
 * scroll position) true by construction: the chat subtree is never disposed
 * when the sidebar's `AnimatedVisibility` leaves composition.
 */
@Composable
private fun ShellLayoutHarness(windowWidthDp: Dp, reducedMotion: Boolean = false) {
    var state by remember { mutableStateOf(ShellLayoutState()) }
    BoxWithConstraints(Modifier.width(windowWidthDp).fillMaxSize()) {
        val measuredWidthDp = maxWidth.value
        val isSidebarVisible = state.isSidebarVisible &&
            !ShellLayoutReducer.defaultCollapsedForWidth(measuredWidthDp)
        LaunchedEffect(measuredWidthDp) {
            state = ShellLayoutReducer.reduce(state, ShellLayoutEvent.WindowWidthChanged(measuredWidthDp))
        }
        Row {
            DesktopSidebarToggleButton(
                collapsed = !isSidebarVisible,
                onToggle = {
                    if (!ShellLayoutReducer.defaultCollapsedForWidth(measuredWidthDp)) {
                        state = ShellLayoutReducer.reduce(state, ShellLayoutEvent.ToggleSidebar)
                    }
                },
            )
            DesktopCollapsibleSidebar(visible = isSidebarVisible, reducedMotion = reducedMotion) {
                Text("Memory", modifier = Modifier.testTag(SidebarContentTag))
            }
            Column {
                var draft by remember { mutableStateOf("") }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.testTag(ChatComposerTag),
                )
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.testTag("chat-scroll")) {
                    items(100) { index -> Text("message $index") }
                }
            }
        }
    }
}

class DesktopSidebarChromeUiTest {
    @Test
    fun wideWindowShowsSidebarByDefault() = runComposeUiTest {
        setContent { ShellLayoutHarness(windowWidthDp = 1200.dp) }

        onNodeWithTag(SidebarContentTag).assertExists()
    }

    @Test
    fun narrowWindowAutoCollapsesSidebarFullyAbsentNotAnIconRail() = runComposeUiTest {
        setContent { ShellLayoutHarness(windowWidthDp = 500.dp) }

        // AC #3: collapsed means the sidebar's content is gone from the tree
        // entirely, not shrunk to an icon rail sitting alongside it.
        onNodeWithTag(SidebarContentTag).assertDoesNotExist()
        // The single toggle affordance is still present in chrome (AC #1).
        onNodeWithTag(SidebarToggleTestTag).assertExists()
    }

    @Test
    fun toggleClickHidesAndReshowsSidebarContent() = runComposeUiTest {
        setContent { ShellLayoutHarness(windowWidthDp = 1200.dp) }

        onNodeWithTag(SidebarContentTag).assertExists()
        onNodeWithTag(SidebarToggleTestTag).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(SidebarContentTag).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(SidebarContentTag).assertDoesNotExist()

        onNodeWithTag(SidebarToggleTestTag).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(SidebarContentTag).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(SidebarContentTag).assertExists()
    }

    @Test
    fun collapsingSidebarPreservesComposerDraftText() = runComposeUiTest {
        setContent { ShellLayoutHarness(windowWidthDp = 1200.dp) }

        onNodeWithTag(ChatComposerTag).performTextInput("draft in progress")
        onNodeWithText("draft in progress").assertExists()

        onNodeWithTag(SidebarToggleTestTag).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(SidebarContentTag).fetchSemanticsNodes().isEmpty()
        }

        // The composer subtree was never inside the collapsed AnimatedVisibility,
        // so its remembered draft text must still be there.
        onNodeWithText("draft in progress").assertExists()
    }

    @Test
    fun reducedMotionSkipsAnimationSidebarDisappearsImmediately() = runComposeUiTest {
        setContent { ShellLayoutHarness(windowWidthDp = 1200.dp, reducedMotion = true) }

        onNodeWithTag(SidebarContentTag).assertExists()
        onNodeWithTag(SidebarToggleTestTag).performClick()

        // With reduced motion the exit transition is EnterTransition/ExitTransition.None,
        // so the very next frame already reflects the collapsed state — no
        // waitUntil polling loop needed.
        onNodeWithTag(SidebarContentTag).assertDoesNotExist()
    }
}
