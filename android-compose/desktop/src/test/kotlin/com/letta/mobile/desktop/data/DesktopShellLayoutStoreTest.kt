package com.letta.mobile.desktop.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopShellLayoutStoreTest {
    @Test
    fun loadReturnsNullWhenNothingWasPersisted() {
        val dir = Files.createTempDirectory("shell-layout-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        assertNull(store.load("backend-a"))
    }

    @Test
    fun saveThenLoadRoundTripsCollapsedAndWidth() {
        val dir = Files.createTempDirectory("shell-layout-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        store.save("backend-a", PersistedShellLayout(collapsedPreference = true, sidebarWidthDp = 280f))
        val loaded = store.load("backend-a")

        assertEquals(true, loaded?.collapsedPreference)
        assertEquals(280f, loaded?.sidebarWidthDp)
    }

    @Test
    fun differentBackendsDoNotShareState() {
        val dir = Files.createTempDirectory("shell-layout-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        store.save("backend-a", PersistedShellLayout(collapsedPreference = true, sidebarWidthDp = 280f))
        store.save("backend-b", PersistedShellLayout(collapsedPreference = false, sidebarWidthDp = 231f))

        assertEquals(true, store.load("backend-a")?.collapsedPreference)
        assertEquals(false, store.load("backend-b")?.collapsedPreference)
        // Switching to a backend that was never explicitly configured must
        // not pick up another backend's saved layout.
        assertNull(store.load("backend-c"))
    }

    @Test
    fun saveIsAtomicAndLeavesNoTempFileBehind() {
        val dir = Files.createTempDirectory("shell-layout-test")
        val path = dir.resolve("shell-layout.properties")
        val store = DesktopShellLayoutStore(path)

        store.save("backend-a", PersistedShellLayout(collapsedPreference = true, sidebarWidthDp = null))

        assertTrue(Files.exists(path))
        val leftoverTempFiles = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("shell-layout") && it != path }
                .toList()
        }
        assertTrue(leftoverTempFiles.isEmpty(), "expected no leftover temp files, found $leftoverTempFiles")
    }

    @Test
    fun secondSavePreservesOtherBackendsEntries() {
        val dir = Files.createTempDirectory("shell-layout-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        store.save("backend-a", PersistedShellLayout(collapsedPreference = true, sidebarWidthDp = 280f))
        store.save("backend-b", PersistedShellLayout(collapsedPreference = true, sidebarWidthDp = 231f))
        store.save("backend-a", PersistedShellLayout(collapsedPreference = false, sidebarWidthDp = 300f))

        assertEquals(false, store.load("backend-a")?.collapsedPreference)
        assertEquals(300f, store.load("backend-a")?.sidebarWidthDp)
        assertEquals(true, store.load("backend-b")?.collapsedPreference)
    }
}
