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
}
