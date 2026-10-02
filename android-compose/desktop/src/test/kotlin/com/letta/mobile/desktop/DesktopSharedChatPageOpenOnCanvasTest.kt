package com.letta.mobile.desktop

import com.letta.mobile.desktop.data.DesktopOpenChatsOnCanvasStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** letta-mobile-bglj6.1: the "Open conversations on the canvas" preference and its store. */
class DesktopSharedChatPageOpenOnCanvasTest {
    private val directory: Path = Files.createTempDirectory("open-chats-on-canvas")
    private val path: Path = directory.resolve("open-chats-on-canvas.properties")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun onByDefaultWithNoSavedPreference() {
        assertTrue(DesktopOpenChatsOnCanvasStore(path).load())
        assertTrue(DesktopOpenChatsOnCanvas(DesktopOpenChatsOnCanvasStore(path)).enabled.value)
    }

    @Test
    fun corruptValueReadsAsOn() {
        path.writeText("chat.openOnCanvas=maybe\n")

        assertTrue(DesktopOpenChatsOnCanvasStore(path).load())
    }

    @Test
    fun storeRoundTripsBothValues() {
        val store = DesktopOpenChatsOnCanvasStore(path)

        store.save(false)
        assertFalse(DesktopOpenChatsOnCanvasStore(path).load())

        store.save(true)
        assertTrue(DesktopOpenChatsOnCanvasStore(path).load())
    }

    @Test
    fun turningItOffAppliesNowAndPersistsAcrossInstances() = runTest {
        val preference = DesktopOpenChatsOnCanvas(
            store = DesktopOpenChatsOnCanvasStore(path),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        preference.setEnabled(false)

        assertFalse(preference.enabled.value)
        assertFalse(DesktopOpenChatsOnCanvas(DesktopOpenChatsOnCanvasStore(path)).enabled.value)
    }
}
