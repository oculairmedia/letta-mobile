package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileCanvasMountScaleTest {
    @Test
    fun bindingOneKeyLeavesOtherKeysContentsAndMtimesUntouched() = runBlocking {
        val root = Files.createTempDirectory("mount-key-isolation")
        val store = FileCanvasMountStore(root, HostId("host"))
        repeat(32) { index -> store.bindIfAbsent(AgentId("a"), ConversationId("c-$index"), CanvasId("board-$index")) }
        val files = records(root)
        files.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(12_000)) }
        val before = files.associateWith { Files.readString(it) to Files.getLastModifiedTime(it) }
        store.bindIfAbsent(AgentId("a"), ConversationId("new-key"), CanvasId("new-board"))
        before.forEach { (file, state) ->
            assertEquals(state.first, Files.readString(file), "Other key contents must remain untouched")
            assertEquals(state.second, Files.getLastModifiedTime(file), "Other key must not be rewritten")
        }
    }

    @Test
    fun bindingsContinueWellPastTheFormerPopulationByteLimit() = runBlocking {
        val root = Files.createTempDirectory("mount-population-size")
        val store = FileCanvasMountStore(root, HostId("host"))
        val payload = "x".repeat(4_096)
        repeat(1_024) { index ->
            val proposed = CanvasId("$index-$payload")
            val outcome = store.bindIfAbsent(AgentId("a"), ConversationId("c-$index"), proposed)
            assertEquals(CanvasMountMutation.Applied(proposed, written = true), outcome)
        }
        assertTrue(records(root).sumOf(Files::size) > 4L * 1024 * 1024, "Actual stored entries must exceed the old table limit")
        val reopened = FileCanvasMountStore(root, HostId("host"))
        assertEquals(CanvasId("1023-$payload"), reopened.readBinding(AgentId("a"), ConversationId("c-1023")))
    }

    @Test
    fun anExistingWinnerIsResyncedWithoutReplacingItsContents() = runBlocking {
        val root = Files.createTempDirectory("mount-existing-winner")
        val store = FileCanvasMountStore(root, HostId("host"))
        store.bindIfAbsent(AgentId("a"), ConversationId("c"), CanvasId("winner"))
        val file = records(root).single()
        Files.setLastModifiedTime(file, FileTime.fromMillis(12_000))
        val before = Files.readAllBytes(file)
        val modified = Files.getLastModifiedTime(file)
        val outcome = store.bindIfAbsent(AgentId("a"), ConversationId("c"), CanvasId("loser"))
        assertEquals(CanvasMountMutation.Applied(CanvasId("winner"), written = false), outcome)
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
        assertEquals(modified, Files.getLastModifiedTime(file), "Existing winner must not be rewritten")
    }

    private fun records(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
    }
}
