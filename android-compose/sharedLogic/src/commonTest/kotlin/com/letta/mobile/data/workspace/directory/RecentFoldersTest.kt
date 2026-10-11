package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-zxk2n.6: the recent working directories, newest first, kept in the settings store. */
class RecentFoldersTest {
    private class MapStore : SecureSettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    @Test
    fun recordingMovesAFolderToTheFrontOnceAndPersistsIt() {
        val store = MapStore()
        val recents = RecentFolders(store, limit = 3)
        recents.record("/a")
        recents.record("/b/")
        recents.record("/a/")
        assertEquals(listOf("/a", "/b"), recents.recent.value)
        assertEquals(listOf("/a", "/b"), RecentFolders(store).recent.value)
    }

    @Test
    fun theListIsCappedAndIgnoresRelativeOrBlankPaths() {
        val recents = RecentFolders(MapStore(), limit = 2)
        listOf("/1", "/2", "/3", "relative", " ").forEach(recents::record)
        assertEquals(listOf("/3", "/2"), recents.recent.value)
    }

    @Test
    fun forgettingDropsAFolder() {
        val store = MapStore()
        val recents = RecentFolders(store)
        recents.record("/a")
        recents.forget("/a/")
        assertEquals(emptyList(), recents.recent.value)
        assertEquals(null, store.values[RecentFolders.DEFAULT_KEY])
    }

    @Test
    fun anUnreadableStoreReadsAsEmpty() {
        val failing = object : SecureSettingsStore by MapStore() {
            override fun getString(key: String, defaultValue: String?): String? = error("locked")
        }
        assertEquals(emptyList(), RecentFolders(failing).recent.value)
    }
}
