package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The durable host store passes the same contract as the in-memory one, restart included. */
class FileCanvasRelayStoreContractTest : CanvasRelayStoreContract() {
    private val dirs = mutableListOf<Path>()
    private val roots = mutableMapOf<CanvasRelayStore, Path>()

    override suspend fun newStore(): CanvasRelayStore {
        val dir = Files.createTempDirectory("canvas-relay-store").also { dirs.add(it) }
        return FileCanvasRelayStore(dir).also { roots[it] = dir }
    }

    override suspend fun reopen(store: CanvasRelayStore): CanvasRelayStore = FileCanvasRelayStore(roots.getValue(store))

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }
}

class FileCanvasRelayStoreTest {

    @Test
    fun aTornLastLineIsDroppedAndTheNextAppendIsKept() = runTest {
        val dir = Files.createTempDirectory("canvas-relay-torn")
        try {
            val store = FileCanvasRelayStore(dir)
            store.append("t", CanvasOp.RemoveElementOp("op-1", "u", 1, "e"), "peer")
            // A crash mid-append: half a line, no newline.
            val ops = Files.list(dir).use { it.findFirst().get() }.resolve("ops.jsonl")
            Files.writeString(ops, """{"cursor":2,"origin":"peer","op":{"ty""", StandardOpenOption.APPEND)

            val reopened = FileCanvasRelayStore(dir)
            assertEquals(1L, reopened.head("t"))
            assertEquals(CanvasRelayAppend(2, duplicate = false), reopened.append("t", CanvasOp.RemoveElementOp("op-2", "u", 2, "e"), "peer"))
            assertEquals(listOf("op-1", "op-2"), FileCanvasRelayStore(dir).readAfter("t", 0).map { it.op.opId })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
