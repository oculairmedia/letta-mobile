package com.letta.mobile.avatar.catalog

import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AvatarCatalogTest {
    private fun model(id: String, name: String = id) = AvatarModel(
        id = id,
        displayName = name,
        uri = "file:///avatars/$id.glb",
        format = AvatarFormat.GLB,
    )

    @Test
    fun refreshLoadsFromStoreSortedByDisplayName() = runTest {
        val store = InMemoryAvatarCatalogStore(
            listOf(model("b", "Zeta"), model("a", "alpha")),
        )
        val catalog = AvatarCatalog(store)

        catalog.refresh()

        assertEquals(listOf("a", "b"), catalog.entries.value.map { it.id })
    }

    @Test
    fun upsertPersistsBeforePublishingAndReplacesById() = runTest {
        val store = InMemoryAvatarCatalogStore()
        val catalog = AvatarCatalog(store)

        catalog.upsert(model("a", "First name"))
        catalog.upsert(model("a", "Renamed"))

        assertEquals(listOf("Renamed"), catalog.entries.value.map { it.displayName })
        assertEquals(listOf("Renamed"), store.load().map { it.displayName })
    }

    @Test
    fun upsertRejectsBlankId() = runTest {
        val catalog = AvatarCatalog(InMemoryAvatarCatalogStore())
        assertFailsWith<IllegalArgumentException> { catalog.upsert(model("  ")) }
    }

    @Test
    fun removeDeletesAndReportsWhetherAnythingChanged() = runTest {
        val store = InMemoryAvatarCatalogStore()
        val catalog = AvatarCatalog(store)
        catalog.upsert(model("a"))

        assertTrue(catalog.remove("a"))
        assertFalse(catalog.remove("a"))
        assertTrue(store.load().isEmpty())
        assertNull(catalog.get("a"))
    }

    @Test
    fun getFindsById() = runTest {
        val catalog = AvatarCatalog(InMemoryAvatarCatalogStore())
        catalog.upsert(model("a"))
        assertEquals("a", catalog.get("a")?.id)
    }

    @Test
    fun upsertWithoutPriorRefreshMergesAgainstPersistedEntries() = runTest {
        // A freshly-constructed catalog over an existing store must not
        // clobber persisted entries when mutated before refresh().
        val store = InMemoryAvatarCatalogStore(listOf(model("existing", "Existing")))
        val catalog = AvatarCatalog(store)

        catalog.upsert(model("new", "New"))

        assertEquals(listOf("existing", "new"), store.load().map { it.id }.sorted())
        assertEquals(listOf("existing", "new"), catalog.entries.value.map { it.id }.sorted())
    }

    @Test
    fun removeWithoutPriorRefreshOperatesOnPersistedEntries() = runTest {
        val store = InMemoryAvatarCatalogStore(listOf(model("existing")))
        val catalog = AvatarCatalog(store)

        assertTrue(catalog.remove("existing"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun interleavedWritersAgainstOneStoreKeepEachOthersEntries() = runTest {
        // Two catalog instances over the same store (e.g. two importer runs
        // into one directory): each upsert merges with the persisted list.
        val store = InMemoryAvatarCatalogStore()
        val first = AvatarCatalog(store)
        val second = AvatarCatalog(store)

        first.upsert(model("from-first"))
        second.upsert(model("from-second"))

        assertEquals(
            listOf("from-first", "from-second"),
            store.load().map { it.id }.sorted(),
        )
    }
}
