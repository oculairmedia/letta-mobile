package com.letta.mobile.wrapper.notebook

import com.github.ajalt.clikt.core.parse
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotebookGrantCommandTest {
    @Test
    fun provisionAndRevokeUseRealHostFile() {
        val file = Files.createTempDirectory("notebook-grant-cli").resolve("grants.json")
        val principal = "a".repeat(64)
        val id = NotebookDocumentId("b".repeat(32))
        val args = listOf("--file", file.toString(), "--principal", principal, "--document", id.value)
        NotebookGrantCommand().parse(args + listOf("--read", "--reference"))
        runBlocking {
            val grant = FileNotebookGrantStore(file).withGrant(principal, id) { it }
            assertTrue(grant?.mayRead == true)
            assertTrue(grant?.mayReference == true)
            assertFalse(grant?.mayEdit == true)
        }
        NotebookGrantCommand().parse(args + "--revoke")
        runBlocking { assertNull(FileNotebookGrantStore(file).withGrant(principal, id) { it }) }
    }
}
