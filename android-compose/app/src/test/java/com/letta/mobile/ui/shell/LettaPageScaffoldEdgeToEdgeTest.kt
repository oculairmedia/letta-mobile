package com.letta.mobile.ui.shell

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android edge to edge at phone size (letta-mobile-ckmub.2.1): a page hosted the way the Android
 * hosts host shared pages (an M3 TopAppBar header over the shared page) draws its container from the
 * very top of the window, behind the status bar, while the app bar starts below it and keeps its own
 * height — the app bar's own status-bar inset is consumed by the scaffold, not added a second time.
 *
 * Robolectric reports no system bars, so the phone's are dispatched to the Compose view as Android
 * would deliver them to an edge-to-edge window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@Tag("integration")
@OptIn(ExperimentalMaterial3Api::class)
class LettaPageScaffoldEdgeToEdgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageDrawsBehindTheStatusBarAndPadsItsBarsOnce() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            MaterialTheme {
                LettaPageScaffold(
                    modifier = Modifier.testTag("page"),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    header = { TopAppBar(title = { Text("Channels") }, modifier = Modifier.testTag("appBar")) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).testTag("content"))
                }
            }
        }
        composeRule.runOnIdle { dispatchPhoneInsets(view) }
        composeRule.waitForIdle()

        val density = view.resources.displayMetrics.density
        val status = (STATUS_BAR_PX / density).dp
        val nav = (NAV_BAR_PX / density).dp
        val window = composeRule.onRoot().getBoundsInRoot().bottom

        assertDpEquals(0.dp, composeRule.top("page"), "page container draws behind the status bar")
        assertDpEquals(status, composeRule.top("appBar"), "app bar starts below the status bar")
        assertDpEquals(APP_BAR_HEIGHT, composeRule.height("appBar"), "app bar is not padded a second time")
        assertDpEquals(status + APP_BAR_HEIGHT, composeRule.top("content"), "content starts under the app bar")
        assertDpEquals(window - nav, composeRule.bottom("content"), "content ends above the navigation bar")
    }

    private fun dispatchPhoneInsets(view: View) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR_PX))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.top(tag: String): Dp =
        onNodeWithTag(tag).getBoundsInRoot().top

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.bottom(tag: String): Dp =
        onNodeWithTag(tag).getBoundsInRoot().bottom

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.height(tag: String): Dp =
        onNodeWithTag(tag).getBoundsInRoot().let { it.bottom - it.top }

    private fun assertDpEquals(expected: Dp, actual: Dp, message: String) =
        assertEquals(message, expected.value, actual.value, 1f)

    private companion object {
        /** A phone's status bar (24dp) and gesture bar (16dp) at xxhdpi. */
        const val STATUS_BAR_PX = 72
        const val NAV_BAR_PX = 48
        val APP_BAR_HEIGHT = 64.dp
    }
}
