package com.letta.mobile.data.controller.node.iroh

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The paired-peer file is shared by the serving wrapper and the host `pair`
 * CLI (separate processes) and by independent instances in one process; its
 * read-modify-write must never drop a new peer or resurrect a revoked one
 * (letta-mobile-by4vm.43).
 */
class PairedPeerStoreTest {
    private val dir = Files.createTempDirectory("paired-peers")
    private val path = dir.resolve("paired-peers.json")

    private fun peer(id: String, at: Long = 1) = PairedPeer(nodeId = id, name = "peer-$id", pairedAtMs = at)

    @Test
    fun racingSaveAndRemoveAcrossInstancesKeepEveryOutcome() = runBlocking {
        val revoked = (0 until 20).map { "r$it" }
        val paired = (0 until 20).map { "s$it" }
        FilePairedPeerStore(path).let { seed -> revoked.forEach { seed.save(peer(it)) } }

        // Two instances = two processes' worth of independent stores over one file.
        val pairer = FilePairedPeerStore(path)
        val revoker = FilePairedPeerStore(path)
        val saves = paired.map { id -> async(Dispatchers.IO) { pairer.save(peer(id)) } }
        val removes = revoked.map { id -> async(Dispatchers.IO) { revoker.remove(id) } }
        saves.awaitAll()
        val removed = removes.awaitAll()

        assertEquals(List(revoked.size) { true }, removed, "every seeded peer was present to revoke")
        assertEquals(paired.toSet(), FilePairedPeerStore(path).list().map { it.nodeId }.toSet())
    }

    @Test
    fun aMutationWaitsForAnotherHolderOfTheLockAndThenRereads() = runBlocking {
        val store = FilePairedPeerStore(path)
        store.save(peer("existing"))
        val lockFile = path.resolveSibling("${path.fileName}.lock")

        val pending = FileChannel.open(lockFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
            channel.lock().use {
                // Stand-in for the other process: start our mutation, then write
                // the other process's own peer while still holding the lock.
                val save = async(Dispatchers.IO) { store.save(peer("ours", at = 3)) }
                AtomicJsonFile.write(
                    path,
                    """[{"nodeId":"existing","name":"peer-existing","pairedAtMs":1},""" +
                        """{"nodeId":"theirs","name":"peer-theirs","pairedAtMs":2}]""",
                )
                delay(HOLD_MS)
                assertFalse(save.isCompleted, "the store must wait while another holder has the lock")
                save
            }
        }
        withTimeout(AWAIT_MS) { pending.await() }

        assertEquals(listOf("existing", "theirs", "ours"), store.list().map { it.nodeId })
    }

    @Test
    fun removingAnAbsentPeerLeavesTheFileUntouched() {
        val store = FilePairedPeerStore(path)
        assertFalse(store.remove("missing"))
        assertFalse(Files.exists(path), "no write happens for a no-op revoke")
    }

    private companion object {
        const val HOLD_MS = 200L
        const val AWAIT_MS = 10_000L
    }
}
