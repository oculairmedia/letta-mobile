package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layout history kept beside the config: each committed change keeps the layout it replaced,
 * bounded, through a restart, with undo stepping back and reset itself undoable.
 */
class ShellLayoutHistoryTest {
    private val settings = InMemorySecureSettingsStore()

    private fun store() = ShellLayoutConfigStore(settings, KEY)

    /** The default layout with only [count] of Home's sections. */
    private fun homeWith(count: Int) = ShellLayoutConfig.Default.copy(
        lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.HOME to HomeViews.Default.take(count).map(::ModuleSlot)),
    )

    @Test
    fun aCommitKeepsTheReplacedLayoutAndUndoRestoresIt() {
        val store = store()
        assertEquals(ShellLayoutConfig.Default, store.commit(homeWith(2)))
        assertEquals(homeWith(2), store.config.value)
        assertEquals(listOf(ShellLayoutConfig.Default), store.history)

        assertEquals(ShellLayoutConfig.Default, store.undo())
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        assertTrue(store.history.isEmpty())
        assertNull(store.undo(), "nothing left to undo")
    }

    @Test
    fun theHistorySurvivesARestart() {
        store().commit(homeWith(3))
        store().commit(homeWith(2))

        val restarted = store()
        assertEquals(homeWith(2), restarted.config.value)
        assertEquals(homeWith(3), restarted.undo())
        assertEquals(ShellLayoutConfig.Default, store().undo())
    }

    @Test
    fun theHistoryKeepsOnlyTheNewestChanges() {
        val store = store()
        (1..ShellLayoutConfigStore.HISTORY_LIMIT + 5).forEach { store.commit(homeWith(it % 7 + 1).copy(version = it + 100)) }
        assertEquals(ShellLayoutConfigStore.HISTORY_LIMIT, store.history.size)
        assertEquals(ShellLayoutConfigStore.HISTORY_LIMIT, store().history.size)
    }

    @Test
    fun resetIsUndoable() {
        val store = store()
        store.commit(homeWith(1))
        assertEquals(homeWith(1), store.resetToDefault())
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        assertNull(store.resetToDefault(), "already the default: nothing kept")
        assertEquals(homeWith(1), store.undo())
    }

    @Test
    fun aCommitOfTheSameLayoutOrOverANewerOneKeepsNothing() {
        val store = store()
        assertNull(store.commit(ShellLayoutConfig.Default))
        assertNull(store.commit(homeWith(2), expected = homeWith(3)))
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        assertTrue(store.history.isEmpty())
    }

    @Test
    fun undoingOneChangeNeverUndoesALaterOne() {
        val store = store()
        store.commit(homeWith(3))
        store.commit(homeWith(2))
        assertNull(store.undo(ifCurrent = homeWith(3)))
        assertEquals(homeWith(2), store.config.value)
        assertEquals(homeWith(3), store.undo(ifCurrent = homeWith(2)))
    }

    @Test
    fun aPlainSaveKeepsNoHistory() {
        val store = store()
        store.save(homeWith(2))
        assertTrue(store.history.isEmpty())
        assertNull(store.undo())
    }

    @Test
    fun anUnreadableHistoryReadsAsEmpty() {
        settings.putString(KEY + ShellLayoutConfigStore.HISTORY_SUFFIX, "[{not json")
        assertTrue(store().history.isEmpty())
        settings.putString(KEY + ShellLayoutConfigStore.HISTORY_SUFFIX, """{"version":3}""")
        assertTrue(store().history.isEmpty())
        val store = store()
        store.commit(homeWith(2))
        assertEquals(ShellLayoutConfig.Default, store().undo())
    }

    private companion object {
        const val KEY = "shell.test.lanes"
    }
}
