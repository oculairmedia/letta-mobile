package com.letta.mobile.ui.canvas.plugin

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The page channel between a view bridge and its page (letta-mobile-s416w.13). */
class PluginPageChannelTest {

    @Test
    fun pagePostsReachTheBridgeAndBridgeSendsReachThePageInOrder() = runTest {
        val channel = PluginPageChannel()
        assertTrue(channel.postFromPage("a"))
        assertTrue(channel.postFromPage("b"))
        channel.send("x")
        channel.send("y")

        assertEquals(listOf("a", "b"), channel.incoming.take(2).toList())
        assertEquals(listOf("x", "y"), channel.toPage.take(2).toList())
    }

    @Test
    fun aFloodPastTheBufferIsDroppedNotHeld() = runTest {
        val channel = PluginPageChannel(capacity = 2)
        assertTrue(channel.postFromPage("1"))
        assertTrue(channel.postFromPage("2"))
        assertFalse(channel.postFromPage("3"))

        assertEquals(listOf("1", "2"), channel.incoming.take(2).toList())
    }

    @Test
    fun nothingPassesAfterClose() = runTest {
        val channel = PluginPageChannel()
        channel.postFromPage("before")
        channel.close()

        assertFalse(channel.postFromPage("after"))
        channel.send("ignored")
        assertEquals("before", channel.incoming.first())
        assertEquals(emptyList(), channel.toPage.toList())
    }
}
