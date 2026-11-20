package com.letta.mobile.data.clientstore

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientStoreEntriesTest {
    @Test
    fun everyEntryIsDeclaredOnce() {
        val duplicates = ClientStoreEntries.all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate declarations: $duplicates")
    }

    @Test
    fun noKeyFamilyContainsAnotherFamily() {
        val families = ClientStoreEntries.all.filter { it.isPrefix }
        val overlaps = families.flatMap { outer ->
            families.filter { it !== outer && it.name.startsWith(outer.name) }.map { outer.name to it.name }
        }
        assertTrue(overlaps.isEmpty(), "overlapping families: $overlaps")
    }

    @Test
    fun deviceFactsAreJustifiedAndFew() {
        val facts = ClientStoreEntries.all.mapNotNull { it.kind as? ClientStoreKind.DeviceFact }
        facts.forEach { assertTrue(it.justification.isNotBlank()) }
        assertTrue(facts.size <= 3, "device facts must stay tiny: ${facts.size}")
        assertFailsWith<IllegalArgumentException> { ClientStoreKind.DeviceFact("  ") }
    }

    @Test
    fun userStateIsNotAClientStoreEntry() {
        val userState = listOf("core.objectPins.v1", "conversations.pinned", "theme", "chat.fontScale", "conversations.archived")
        userState.forEach { assertNull(ClientStoreEntries.declarationOf(it), it) }
    }

    @Test
    fun entriesAreAddressedTheirOwnWay() {
        assertEquals("configs", ClientStoreEntries.BackendConfigs.storageKey)
        val mascot = ClientStoreEntries.MascotIdentity
        assertEquals(mascot, ClientStoreEntries.declarationOf(mascot.keyFor("a1.avatar_style")))
        assertFailsWith<IllegalStateException> { ClientStoreEntries.WebBackend.storageKey }
        assertFailsWith<IllegalStateException> { ClientStoreEntries.BackendConfigs.keyFor("x") }
    }

    @Test
    fun clearAllWipesEveryCache() = runTest {
        val wiped = mutableListOf<ClientStoreEntry>()
        val caches = ClientStoreEntries.caches.map { declared ->
            object : DisposableCache {
                override val entry = declared
                override suspend fun clearAll() {
                    wiped += declared
                }
            }
        }
        ClientStoreEntries.clearAll(caches)
        assertEquals(ClientStoreEntries.caches, wiped)
    }
}
