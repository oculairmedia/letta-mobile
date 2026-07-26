package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

class ProcessHandleControllerTest {

    @Test
    fun `bounded buffer overwrites old output when capacity exceeded`() {
        val tempDir = System.getProperty("java.io.tmpdir")
        val javaFile = File(tempDir, "EchoMore.java")
        val expectedSuffix = "901234567890" // 12 bytes
        val fullOutput = "12345678901234567890" // 20 bytes total

        javaFile.writeText(
            """
            public class EchoMore {
                public static void main(String[] args) {
                    System.out.print("$fullOutput");
                }
            }
            """.trimIndent()
        )

        val javaExecutable = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val capacity = 12

        val controller = ProcessHandleController(
            command = listOf(javaExecutable, javaFile.absolutePath),
            diagnosticBufferBytes = capacity
        )

        controller.spawn()
        val exitCode = controller.awaitExit()

        assertEquals(0, exitCode, "Java process should exit successfully")

        // Wait a small amount to let daemon drain output reliably
        Thread.sleep(100)

        val diagnostics = controller.drainDiagnostics(100)

        assertEquals(expectedSuffix, diagnostics, "Diagnostics should only contain the newest $capacity bytes")

        javaFile.delete()
    }

    @Test
    fun `destroyTree returns silently when no process was spawned`() {
        val controller = ProcessHandleController(command = listOf("echo", "test"))

        // spawn() was never called, so the handle is null. destroyTree must be a no-op
        // rather than throwing — cancel/teardown paths call it unconditionally.
        controller.destroyTree()
    }

    @Test
    fun `toHttpReadyUri falls back to 4500 when the listen url has no port`() {
        val uri = HttpReadinessProbe.toHttpReadyUri("http://localhost", "/readyz")

        assertEquals(4500, uri.port)
        assertEquals(URI("http://localhost:4500/readyz"), uri)
    }

    @Test
    fun `toHttpReadyUri preserves an explicit port`() {
        val uri = HttpReadinessProbe.toHttpReadyUri("http://localhost:8080", "/readyz")

        assertEquals(8080, uri.port)
        assertEquals(URI("http://localhost:8080/readyz"), uri)
    }
}
