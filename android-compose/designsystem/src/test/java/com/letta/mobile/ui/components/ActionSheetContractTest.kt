package com.letta.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Tag("unit")
class ActionSheetContractTest {

    private val source = readSource("ui/components/ActionSheet.kt")

    @Test
    fun `action sheet uses modal bottom sheet container`() {
        assertTrue(source.contains("ModalBottomSheet("))
        assertTrue(source.contains("rememberModalBottomSheetState()"))
    }

    @Test
    fun `action sheet item uses standardized list item row`() {
        assertTrue(source.contains("ListItem("))
        assertTrue(source.contains("LettaIconSizing.ListLeading"))
        assertTrue(source.contains("ListItemDefaults.colors(containerColor = Color.Transparent)"))
    }

    @Test
    fun `destructive actions use error color`() {
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
