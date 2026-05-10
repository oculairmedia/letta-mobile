package com.letta.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Tag("unit")
class ConfirmDialogContractTest {

    private val source = readSource("ui/components/ConfirmDialog.kt")

    @Test
    fun `confirm dialog does not render when hidden`() {
        assertTrue(source.contains("if (!show) return"))
    }

    @Test
    fun `confirm dialog exposes message and custom content overloads`() {
        assertTrue(source.contains("message: String"))
        assertTrue(source.contains("content: @Composable () -> Unit"))
    }

    @Test
    fun `destructive confirm button uses error color`() {
        assertTrue(source.contains("destructive") && source.contains("MaterialTheme.colorScheme.error"))
    }
}

private fun readSource(relativePath: String): String {
    val userDir = Path.of(System.getProperty("user.dir"))
    val candidates = listOf(
        userDir.resolve("src/main/java/com/letta/mobile/$relativePath"),
        userDir.resolve("designsystem/src/main/java/com/letta/mobile/$relativePath"),
    )
    val path = candidates.first { it.exists() }
    return String(Files.readAllBytes(path))
}
