package com.letta.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Tag("unit")
class ErrorContentContractTest {

    private val source = readSource("ui/components/ErrorContent.kt")

    @Test
    fun `error content centers icon and message`() {
        assertTrue(source.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertTrue(source.contains("verticalArrangement = Arrangement.Center"))
        assertTrue(source.contains("textAlign = TextAlign.Center"))
    }

    @Test
    fun `error content uses error theme color`() {
        assertTrue(source.contains("color = MaterialTheme.colorScheme.error"))
        assertTrue(source.contains("tint = MaterialTheme.colorScheme.error"))
    }

    @Test
    fun `error content uses large 64dp icon size`() {
        assertTrue(source.contains("Modifier.size(64.dp)"))
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
