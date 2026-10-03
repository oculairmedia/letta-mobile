@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live-view slot (letta-mobile-s416w.13): with no [PluginViewHost] provided (the default) an
 * element is drawn by the fallback card, and with one the slot hands the element to it.
 */
class PluginViewSlotUiTest {

    private val view = PluginElementView(
        CanvasPluginElement(id = "pe-live", type = "ext:letta.example/widget", fallback = CanvasPluginFallback(title = "Widget")),
    )
    private val chrome = PluginElementChrome(moveHandle = Modifier, open = null, fail = {})

    private object RecordingHost : PluginViewHost {
        @Composable
        override fun View(session: PluginViewSession, modifier: Modifier, onFailure: (reason: String) -> Unit) = Unit
    }

    private fun ComposeUiTest.show(host: PluginViewHost?, onLive: (PluginViewHost) -> Unit) {
        setContent {
            CompositionLocalProvider(LocalPluginViewHost provides host) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.fillMaxSize()) {
                        PluginViewSlot(view, chrome) { provided ->
                            onLive(provided)
                            Box(Modifier.fillMaxSize().testTag(CanvasPluginTestTags.live(view.element.id)))
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun withoutAHostTheFallbackCardDrawsTheElement() = runDesktopComposeUiTest(width = SIZE, height = SIZE) {
        val hosts = mutableListOf<PluginViewHost>()
        show(host = null) { hosts += it }

        onNodeWithTag(CanvasPluginTestTags.card("pe-live")).assertExists()
        onAllNodesWithTag(CanvasPluginTestTags.live("pe-live")).assertCountEquals(0)
        assertEquals(emptyList(), hosts)
    }

    @Test
    fun aProvidedHostGetsTheElementInsteadOfTheCard() = runDesktopComposeUiTest(width = SIZE, height = SIZE) {
        val hosts = mutableListOf<PluginViewHost>()
        show(host = RecordingHost) { hosts += it }

        onNodeWithTag(CanvasPluginTestTags.live("pe-live")).assertExists()
        onAllNodesWithTag(CanvasPluginTestTags.card("pe-live")).assertCountEquals(0)
        assertEquals(RecordingHost, hosts.distinct().single())
    }

    private companion object {
        const val SIZE = 400
    }
}
