package com.letta.mobile.desktop.data

import com.letta.mobile.ui.chat.session.ChatDockGeometry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopChatDockGeometryStoreTest {
    private val directory: Path = Files.createTempDirectory("chat-dock")
    private val path: Path = directory.resolve("chat-dock.properties")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun aMissingFileIsTheDefaultPlacement() {
        assertEquals(ChatDockGeometry.Default, DesktopChatDockGeometryStore(path).load())
    }

    @Test
    fun savedPlacementRoundTrips() {
        val geometry = ChatDockGeometry(anchorX = 0.25f, anchorY = 0.6f, widthDp = 540f, heightDp = 410f, collapsed = true)
        DesktopChatDockGeometryStore(path).save(geometry)
        assertEquals(geometry, DesktopChatDockGeometryStore(path).load())
    }

    @Test
    fun theDefaultSizeStaysUnset() {
        DesktopChatDockGeometryStore(path).save(ChatDockGeometry.Default)
        assertEquals(ChatDockGeometry.Default, DesktopChatDockGeometryStore(path).load())
    }

    @Test
    fun damagedValuesFallBackToTheDefaults() {
        path.writeText("chat.dock.anchorX=left\nchat.dock.anchorY=9\nchat.dock.widthDp=-4\nchat.dock.collapsed=maybe\n")
        assertEquals(ChatDockGeometry(anchorX = 0.5f, anchorY = 1f), DesktopChatDockGeometryStore(path).load())
    }
}
