package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.RowIdentity
import com.letta.mobile.data.runtime.StoredRowRef
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTurnIdentityStoreTest {
    private val directory = Files.createTempDirectory("turn-identity").toFile()

    private fun entry(n: Int) = StoredRowRef("ui-msg-$n", "assistant_message") to RowIdentity("lm-$n", "turn-${n / 10}")

    @Test
    fun appendThenLoadRoundTrips() = runTest {
        val store = FileTurnIdentityStore(directory)
        val first = mapOf(entry(1), entry(2))
        store.append("conv/odd id", first)
        store.append("conv/odd id", mapOf(entry(3)))

        val reloaded = FileTurnIdentityStore(directory).load("conv/odd id")

        assertEquals(first + entry(3), reloaded)
        assertEquals(emptyMap(), store.load("other"))
    }

    @Test
    fun loadBoundsToNewestEntries() = runTest {
        val store = FileTurnIdentityStore(directory, maxEntries = 5)
        (1..12).forEach { store.append("c", mapOf(entry(it))) }

        val loaded = store.load("c")

        assertEquals((8..12).map { entry(it).first }, loaded.keys.toList())
    }

    @Test
    fun aLaterLineForTheSameRowWins() = runTest {
        val store = FileTurnIdentityStore(directory)
        val ref = StoredRowRef("ui-msg-1", "assistant_message")
        store.append("c", mapOf(ref to RowIdentity("ui-msg-1:assistant_message", "t")))
        store.append("c", mapOf(ref to RowIdentity("lm-real", "t")))

        assertEquals(RowIdentity("lm-real", "t"), store.load("c").getValue(ref))
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun aTornOrForeignVersionLineIsSkippedNotServed() = runTest {
        val store = FileTurnIdentityStore(directory)
        store.append("c", mapOf(entry(1)))
        java.io.File(directory, "c.jsonl").appendText("{\"v\":2,\"d\":\"x\",\"t\":\"t\",\"l\":\"l\",\"n\":\"n\"}\n{\"v\":1,\"d\":\"ui-msg-\n")
        store.append("c", mapOf(entry(2)))

        assertEquals(listOf(entry(1).first, entry(2).first), store.load("c").keys.toList())
    }
}
