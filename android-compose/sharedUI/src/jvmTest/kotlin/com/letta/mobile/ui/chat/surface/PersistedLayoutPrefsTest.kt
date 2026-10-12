@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutPrefs
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.14: the shared hosts that read and write a platform's layout prefs. */
class PersistedLayoutPrefsTest {
    @Test
    fun theDockLoadsItsPlacementAndWritesAMoveBack() = runComposeUiTest {
        val stored = ChatDockGeometry.Default.copy(anchorX = 0.25f)
        val saved = mutableListOf<ChatDockGeometry>()
        val storage = LayoutPrefStorage(load = { stored }, save = { saved += it })
        lateinit var geometry: MutableState<ChatDockGeometry?>
        setContent { geometry = rememberChatDockGeometry(storage) }
        waitUntil { geometry.value != null }
        assertEquals(stored, geometry.value)
        runOnIdle { assertTrue(saved.isEmpty(), "an unmoved panel never writes") }

        val moved = stored.copy(anchorY = 0.75f)
        runOnIdle { geometry.value = moved }
        waitUntil(timeoutMillis = 5_000) { saved.isNotEmpty() }
        assertEquals(listOf(moved), saved)
    }

    @Test
    fun aFailingLoadFallsBackToTheDefaultPlacement() = runComposeUiTest {
        val storage = LayoutPrefStorage<ChatDockGeometry>(load = { error("damaged") }, save = {})
        lateinit var geometry: MutableState<ChatDockGeometry?>
        setContent { geometry = rememberChatDockGeometry(storage) }
        waitUntil { geometry.value != null }
        assertEquals(ChatDockGeometry.Default, geometry.value)
    }

    @Test
    fun theBubbleHostProvidesTheStoredLayout() = runComposeUiTest {
        val stored = CanvasBubbleLayout.Desktop
        val storage = LayoutPrefStorage(load = { stored }, save = {})
        var prefs: CanvasBubbleLayoutPrefs? = null
        setContent {
            CanvasBubbleLayoutHost(CanvasBubbleLayout.Desktop, storage) { prefs = LocalCanvasBubbleLayoutPrefs.current }
        }
        runOnIdle { assertEquals(stored, assertNotNull(prefs).layout.value) }
    }
}
