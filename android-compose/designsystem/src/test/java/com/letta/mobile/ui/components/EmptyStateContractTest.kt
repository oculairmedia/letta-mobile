package com.letta.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Tag("unit")
class EmptyStateContractTest {

    private val source = readSource("ui/components/EmptyState.kt")

    @Test
    fun `empty state centers icon and message`() {
        assertTrue(source.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertTrue(source.contains("verticalArrangement = Arrangement.Center"))
    }

    @Test
    fun `empty state uses accessible message as icon content description`() {
        assertTrue(source.contains("contentDescription = message"))
    }

    @Test
    fun `empty state uses surface variant tone`() {
        assertTrue(source.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
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
