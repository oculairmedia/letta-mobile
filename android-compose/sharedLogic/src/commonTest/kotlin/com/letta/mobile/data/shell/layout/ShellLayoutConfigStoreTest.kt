package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shell layout kept between launches: a second store over the same settings is a restart. */
class ShellLayoutConfigStoreTest {
    private val settings = InMemorySecureSettingsStore()
    private val notes = ModuleSlot(RuntimeViewId("canvas"), objectRef = "project-notes")
    private val withNotes = ShellLayoutConfig.Default.copy(
        lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.SIDE to listOf(notes)),
    )

    private fun store(backend: String? = "backend-a") = ShellLayoutConfigStore.forBackend(settings, backend)

    @Test
    fun nothingStoredReadsAsTheDefault() {
        assertEquals(ShellLayoutConfig.Default, store().config.value)
    }

    @Test
    fun aSavedConfigIsReadBackAfterARestart() {
        store().save(withNotes)

        assertEquals(withNotes, store().config.value)
    }

    @Test
    fun anUnreadableValueReadsAsTheDefault() {
        settings.putString("shell.backend-a.lanes", "{not a layout")

        assertEquals(ShellLayoutConfig.Default, store().config.value)
    }

    @Test
    fun anOlderStoredConfigIsUpgradedOnRead() {
        val old = ShellLayoutConfig(version = 0, lanes = mapOf(ShellLanes.MAIN to listOf(notes)))
        settings.putString("shell.backend-a.lanes", old.encode())

        val read = store().config.value

        assertEquals(ShellLayoutConfig.VERSION, read.version)
        assertEquals(listOf(notes, ModuleSlot(ShellViews.PAGE)), read.lane(ShellLanes.MAIN))
        assertEquals(listOf(ModuleSlot(ShellViews.SIDE_PANE)), read.lane(ShellLanes.SIDE))
    }

    @Test
    fun aSaveIsWhatTheStoreThenShows() {
        val store = store()

        store.save(withNotes)

        assertEquals(withNotes, store.config.value)
    }

    @Test
    fun anOlderConfigIsSavedUpgraded() {
        val store = store()

        store.save(ShellLayoutConfig(version = 0, lanes = mapOf(ShellLanes.MAIN to listOf(notes))))

        assertEquals(ShellLayoutConfig.VERSION, store.config.value.version)
        assertEquals(store.config.value, store().config.value)
    }

    @Test
    fun eachBackendKeepsItsOwnLayout() {
        store("backend-a").save(withNotes)

        assertEquals(ShellLayoutConfig.Default, store("backend-b").config.value)
    }
}
