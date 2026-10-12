@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LettaPageScaffold] applies the window's insets exactly once (letta-mobile-ckmub.2.1): the header
 * sits below the status bar, the bottom bar above the navigation bar or the keyboard, the content
 * gets whatever the bars did not take, and a child that pads for the same insets again gets nothing.
 * Desktop windows report no insets, so fake ones stand in for a phone's.
 */
class LettaPageScaffoldInsetsTest {
    private val phone = WindowInsets(left = 0.dp, top = STATUS, right = 0.dp, bottom = NAV)
    private val noKeyboard = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
    private val keyboard = WindowInsets(0.dp, 0.dp, 0.dp, KEYBOARD)

    private fun ComposeUiTest.page(
        imeInsets: WindowInsets = noKeyboard,
        header: Boolean = true,
        bottomBar: Boolean = true,
        wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) = setContent {
        wrap {
            LettaPageScaffold(
                modifier = Modifier.size(WIDTH, HEIGHT),
                header = if (header) ({ Box(Modifier.fillMaxWidth().height(BAR).testTag("header")) }) else null,
                bottomBar = if (bottomBar) ({ Box(Modifier.fillMaxWidth().height(BAR).testTag("bottom")) }) else null,
                insets = phone,
                imeInsets = imeInsets,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).testTag("content")) {
                    // A child padding for the very same insets again: consumed, so it adds nothing.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(phone.union(imeInsets)).testTag("child"))
                }
            }
        }
    }

    @Test
    fun headerBelowStatusBarAndBottomBarAboveNavigationBar() = runComposeUiTest {
        page()
        assertEquals(STATUS, top("header"))
        assertEquals(STATUS + BAR, top("content"))
        assertEquals(HEIGHT - NAV, bottom("bottom"))
        assertEquals(HEIGHT - NAV - BAR, bottom("content"))
    }

    @Test
    fun childPaddingForTheSameInsetsAddsNothing() = runComposeUiTest {
        page()
        assertEquals(top("content"), top("child"))
        assertEquals(bottom("content"), bottom("child"))
    }

    @Test
    fun contentTakesTheInsetsNoBarTook() = runComposeUiTest {
        page(header = false, bottomBar = false)
        assertEquals(STATUS, top("content"))
        assertEquals(HEIGHT - NAV, bottom("content"))
        assertEquals(STATUS, top("child"))
    }

    @Test
    fun keyboardLiftsTheBottomBarInsteadOfTheNavigationBar() = runComposeUiTest {
        page(imeInsets = keyboard)
        assertEquals(HEIGHT - KEYBOARD, bottom("bottom"))
        assertEquals(STATUS, top("header"))
    }

    @Test
    fun insetsAnAncestorAlreadyConsumedAreNotPaddedAgain() = runComposeUiTest {
        val statusBarTakenAbove = PaddingValues(top = STATUS)
        page(wrap = { inner -> Box(Modifier.consumeWindowInsets(statusBarTakenAbove)) { inner() } })
        assertEquals(0.dp, top("header"))
        assertEquals(HEIGHT - NAV, bottom("bottom"))
    }

    @Test
    fun splitGivesEachSideToExactlyOneSlot() {
        val frame = PaddingValues(start = 4.dp, top = STATUS, end = 6.dp, bottom = NAV)
        val split = splitPageInsets(frame, hasHeader = true, hasBottomBar = true, layoutDirection = LayoutDirection.Ltr)
        assertEquals(STATUS, split.header.calculateTopPadding() + split.content.calculateTopPadding())
        assertEquals(NAV, split.bottomBar.calculateBottomPadding() + split.content.calculateBottomPadding())
        assertEquals(0.dp, split.content.calculateTopPadding())
        val bare = splitPageInsets(frame, hasHeader = false, hasBottomBar = false, layoutDirection = LayoutDirection.Ltr)
        assertEquals(STATUS, bare.content.calculateTopPadding())
        assertEquals(NAV, bare.floatingAction.calculateBottomPadding())
    }

    private fun ComposeUiTest.top(tag: String): Dp = onNodeWithTag(tag).getBoundsInRoot().top - rootTop()

    private fun ComposeUiTest.bottom(tag: String): Dp = onNodeWithTag(tag).getBoundsInRoot().bottom - rootTop()

    private fun ComposeUiTest.rootTop(): Dp = onRoot().getBoundsInRoot().top

    private companion object {
        val STATUS = 24.dp
        val NAV = 48.dp
        val KEYBOARD = 300.dp
        val BAR = 56.dp
        val WIDTH = 400.dp
        val HEIGHT = 600.dp // fits the default test window (1024x768), so the page is not clamped
    }
}
