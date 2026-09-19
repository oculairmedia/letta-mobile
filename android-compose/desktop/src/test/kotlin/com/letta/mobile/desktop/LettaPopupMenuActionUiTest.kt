@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A menu item has to do what it says, even though choosing it removes the menu.
 *
 * Callers dismiss by removing the popup - `if (menuOpen) { LettaPopupMenu(...) }` - so anything
 * that defers the action inside this composable is cancelled by the dismissal itself. That is not
 * theoretical: it shipped, and every menu in the app quietly stopped working while still looking
 * and animating exactly as before.
 */
class LettaPopupMenuActionUiTest {

    @Test
    fun aChosenActionRunsOnceEvenWhenDismissalRemovesTheMenu() = runComposeUiTest {
        val runs = AtomicInteger(0)

        setContent {
            var open by remember { mutableStateOf(true) }
            Box(modifier = Modifier.fillMaxSize()) {
                // The shape every caller uses: the dismiss handler takes the popup out of the
                // composition entirely.
                if (open) {
                    LettaPopupMenu(
                        expanded = true,
                        onDismiss = { open = false },
                        items = listOf(LettaMenuItem(label = "Open canvas") { runs.incrementAndGet() }),
                    )
                }
            }
        }

        onNodeWithText("Open canvas").performClick()

        waitUntil(timeoutMillis = 5000) { runs.get() == 1 }
        // And exactly once: a deferral that re-fires on recomposition would open two canvases.
        repeat(10) { waitForIdle() }
        assertEquals(1, runs.get())
    }

    @Test
    fun dismissingWithoutChoosingRunsNothing() = runComposeUiTest {
        val runs = AtomicInteger(0)

        setContent {
            var open by remember { mutableStateOf(true) }
            Box(modifier = Modifier.fillMaxSize()) {
                if (open) {
                    LettaPopupMenu(
                        expanded = true,
                        onDismiss = { open = false },
                        items = listOf(LettaMenuItem(label = "Open canvas") { runs.incrementAndGet() }),
                    )
                }
            }
        }

        // Closing the menu without choosing anything - the outside-click path.
        onNodeWithText("Open canvas").assertIsDisplayed()
        repeat(20) { waitForIdle() }

        assertEquals(0, runs.get())
    }

    @Test
    fun aDisabledItemRunsNothing() = runComposeUiTest {
        val runs = AtomicInteger(0)

        setContent {
            LettaPopupMenu(
                expanded = true,
                onDismiss = {},
                items = listOf(
                    LettaMenuItem(label = "Open canvas", enabled = false) { runs.incrementAndGet() },
                ),
            )
        }

        onNodeWithText("Open canvas").performClick()
        repeat(20) { waitForIdle() }

        assertEquals(0, runs.get())
    }
}
