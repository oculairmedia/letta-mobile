package com.letta.mobile.data.desktopshell

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellLayoutStoreTest {
    private val settings = InMemorySecureSettingsStore()

    @Test
    fun nothingStoredReadsAsTheWidthDerivedDefault() {
        assertEquals(ShellLayoutState(), ShellLayoutStore(settings, "a").load())
    }

    @Test
    fun anExplicitChoiceRoundTripsWithItsWidth() {
        val store = ShellLayoutStore(settings, "a")
        val chosen = ShellLayoutState(collapsedPreference = true, hasExplicitPreference = true, sidebarWidthDp = 280f)

        store.record(ShellLayoutState(), chosen)

        val restored = ShellLayoutStore(settings, "a").load()
        assertTrue(restored.collapsedPreference)
        assertTrue(restored.hasExplicitPreference)
        assertEquals(280f, restored.sidebarWidthDp)
    }

    @Test
    fun keysAreIndependent() {
        val chosen = ShellLayoutState(collapsedPreference = true, hasExplicitPreference = true)
        ShellLayoutStore(settings, "a").record(ShellLayoutState(), chosen)

        assertFalse(ShellLayoutStore(settings, "b").load().hasExplicitPreference)
    }

    @Test
    fun aDamagedRecordReadsAsTheDefault() {
        settings.putString("a", "not json")

        assertEquals(ShellLayoutState(), ShellLayoutStore(settings, "a").load())
    }

    @Test
    fun widthOnlyChangesDoNotPersistAfterAnExplicitChoice() {
        val explicit = ShellLayoutState(collapsedPreference = true, hasExplicitPreference = true, windowWidthDp = 1_000f)

        assertFalse(ShellLayoutStore.shouldPersist(explicit, explicit.copy(windowWidthDp = 1_001f)))
        assertTrue(ShellLayoutStore.shouldPersist(explicit, explicit.copy(sidebarWidthDp = 280f)))
    }
}
