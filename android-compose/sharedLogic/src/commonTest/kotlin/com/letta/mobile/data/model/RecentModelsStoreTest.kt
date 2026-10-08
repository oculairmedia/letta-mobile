package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-bzvro.18 (F18): the recent-models list. */
class RecentModelsStoreTest {
    @Test
    fun recordingMovesAModelToTheFrontWithoutDuplicates() {
        val persisted = ListPersistence(mutableListOf("b", "a"))
        val store = RecentModelsStore(persisted)

        store.record("a")
        store.record("c")

        assertEquals(listOf("c", "a", "b"), store.recent.value)
        assertEquals(listOf("c", "a", "b"), persisted.saved)
    }

    @Test
    fun thePickerSeesEightAndTheFileKeepsTen() {
        val persisted = ListPersistence(mutableListOf())
        val store = RecentModelsStore(persisted)

        (1..12).forEach { store.record("m$it") }

        assertEquals((12 downTo 5).map { "m$it" }, store.recent.value)
        assertEquals((12 downTo 3).map { "m$it" }, persisted.saved)
    }

    @Test
    fun aRecordMergesWhatAnotherClientSavedMeanwhile() {
        val persisted = ListPersistence(mutableListOf("a"))
        val store = RecentModelsStore(persisted)
        persisted.saved = listOf("tui-pick", "a")

        store.record("b")

        assertEquals(listOf("b", "tui-pick", "a"), store.recent.value)
    }

    @Test
    fun aBrokenStoreReadsAsEmptyAndBlankHandlesAreIgnored() {
        val store = RecentModelsStore(object : RecentModelsPersistence {
            override fun load(): List<String> = error("unreadable")
            override fun save(models: List<String>) = error("read-only")
        })

        store.record(" ")
        store.record("a")

        assertEquals(listOf("a"), store.recent.value)
    }

    @Test
    fun theSettingsStoreKeepsOneHandlePerLine() {
        val store = com.letta.mobile.data.chat.runtime.MapSettingsStore()
        val persistence = SettingsStoreRecentModels(store)
        persistence.save(listOf("openai/gpt-x", "anthropic/claude"))

        assertEquals(listOf("openai/gpt-x", "anthropic/claude"), persistence.load())
        persistence.save(emptyList())
        assertNull(store.getString(SettingsStoreRecentModels.DEFAULT_KEY))
    }

    private class ListPersistence(var saved: List<String>) : RecentModelsPersistence {
        override fun load(): List<String> = saved

        override fun save(models: List<String>) {
            saved = models
        }
    }
}
