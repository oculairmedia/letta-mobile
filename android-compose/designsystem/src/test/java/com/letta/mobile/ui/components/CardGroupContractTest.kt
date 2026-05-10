package com.letta.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Tag("unit")
class CardGroupContractTest {

    private val source = readSource("ui/components/CardGroup.kt")

    @Test
    fun `card group keeps grouped corner and spacing tokens`() {
        assertTrue(source.contains("private val CardGroupCorner = 20.dp"))
        assertTrue(source.contains("private val CardGroupItemSpacing = 2.dp"))
        assertTrue(source.contains("private val CardGroupInnerCorner = 4.dp"))
    }

    @Test
    fun `card group animates pressed corner radii`() {
        assertTrue(source.contains("collectIsPressedAsState()"))
        assertTrue(source.contains("animateDpAsState("))
        assertTrue(source.contains("MaterialTheme.motionScheme.fastSpatialSpec()"))
    }

    @Test
    fun `card group defaults use design system list item colors`() {
        assertTrue(source.contains("MaterialTheme.customColors.listItemColors"))
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
