package com.letta.mobile.ui.host

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.search.LettaSearchAnchoredField
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.search.SearchTriggerTestTag
import com.letta.mobile.ui.shell.ShellLongPressMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** letta-mobile-ztdpf: no Compose popup window may outlive the page that opened it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PanePopupLeakTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val rows = listOf(LettaSearchSection("Agents", listOf(LettaSearchRow("a", "Agent A"))))

    @Composable
    private fun App() {
        nav = rememberNavController()
        MaterialTheme {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomePage() }
                composable("other") { Text("Other") }
            }
        }
    }

    @Composable
    private fun HomePage() {
        var query by remember { mutableStateOf("") }
        Column {
            LettaSearchAnchoredField(
                query = query,
                onQueryChange = { query = it },
                sections = rows,
                onRowSelected = {},
                onDismiss = {},
                compact = true,
            )
            ShellLongPressMenu(listOf(LettaMenuItem("Open") { nav.navigate("other") })) {
                Box(Modifier.testTag(ROW)) { Text("Row") }
            }
        }
    }

    @Test
    fun searchPaneClosesWhenThePageNavigatesAway() {
        compose.setContent { App() }
        compose.onNodeWithTag(SearchTriggerTestTag).performClick()
        compose.waitForIdle()
        assertEquals(1, popupWindows().size)
        compose.runOnUiThread { nav.navigate("other") }
        compose.waitForIdle()
        assertEquals("popup windows after navigating away: ${describe()}", 0, popupWindows().size)
    }

    @Test
    fun searchPaneClosesOnBack() {
        compose.setContent { App() }
        compose.runOnUiThread { nav.navigate("other") }
        compose.waitForIdle()
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTriggerTestTag).performClick()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("popup windows after back: ${describe()}", 0, popupWindows().size)
    }

    @Test
    fun rowMenuClosesWhenItsItemNavigates() {
        compose.setContent { App() }
        compose.onNodeWithTag(ROW).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, popupWindows().size)
        compose.onNodeWithTag(com.letta.mobile.ui.shell.ShellRowMenuTags.MENU, useUnmergedTree = true).assertExists()
        compose.runOnUiThread { nav.navigate("other") }
        compose.waitForIdle()
        assertEquals("popup windows after navigating away: ${describe()}", 0, popupWindows().size)
    }

    @Test
    fun searchPaneClosesOnRecreate() {
        compose.setContent { App() }
        compose.onNodeWithTag(SearchTriggerTestTag).performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("popup windows after recreate: ${describe()}", 0, popupWindows().size)
    }

    @Test
    fun searchPaneClosesWhenTheAppStopsAndStaysClosedOnReturn() {
        compose.setContent { App() }
        compose.onNodeWithTag(SearchTriggerTestTag).performClick()
        compose.waitForIdle()
        assertEquals(1, popupWindows().size)
        stopAndResume()
        assertEquals("popup windows after stop/resume: ${describe()}", 0, popupWindows().size)
    }

    @Test
    fun rowMenuClosesWhenTheAppStopsAndStaysClosedOnReturn() {
        compose.setContent { App() }
        compose.onNodeWithTag(ROW).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, popupWindows().size)
        stopAndResume()
        assertEquals("popup windows after stop/resume: ${describe()}", 0, popupWindows().size)
    }

    private fun stopAndResume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertEquals("popup windows while stopped: ${describe()}", 0, popupWindows().size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun roots(): List<Pair<View, WindowManager.LayoutParams>> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        val params = global.getDeclaredField("mParams").apply { isAccessible = true }.get(instance) as List<*>
        return views.indices.map { views[it] as View to params[it] as WindowManager.LayoutParams }
    }

    private fun popupWindows() = roots().filter { (view, lp) ->
        lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL && view.isAttachedToWindow
    }

    private fun describe() = roots().joinToString { (v, lp) -> "${v.javaClass.simpleName}(type=${lp.type}, title=${lp.title}, attached=${v.isAttachedToWindow})" }

    private companion object {
        const val ROW = "row"
    }
}
