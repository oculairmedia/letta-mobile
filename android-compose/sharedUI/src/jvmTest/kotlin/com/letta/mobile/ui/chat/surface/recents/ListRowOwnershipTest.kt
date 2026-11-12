package com.letta.mobile.ui.chat.surface.recents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prevent a row from silently growing a second hover/background implementation. */
class ListRowOwnershipTest {
    @Test
    fun sharedRowOwnsHoverSurfaceCursorAndTooltip() {
        val source = File("src/commonMain/kotlin/com/letta/mobile/ui")
        val shared = File(source, "components/LettaListParts.kt").readText()
        assertTrue(shared.contains("collectIsHoveredAsState()"))
        assertTrue(shared.contains("pointerHoverIcon("))
        assertTrue(shared.contains("tooltip(spec.tooltip)"))
        val paths = listOf(
            "chat/surface/recents/RecentInteractionsList.kt",
            "shell/sidebar/ShellConversationRow.kt",
            "components/list/RosterList.kt",
            "components/list/RosterRowParts.kt",
        )
        for (path in paths) {
            val text = File(source, path).readText().substringBefore("fun RosterRenameField")
            assertEquals(0, Regex("collectIsHoveredAsState\\s*\\(").findAll(text).count(), path)
            assertEquals(0, Regex("\\.background\\s*\\(").findAll(text).count(), path)
            assertEquals(0, Regex("pointerHoverIcon\\s*\\(").findAll(text).count(), path)
            assertEquals(0, Regex("\\.tooltip\\s*\\(").findAll(text).count(), path)
        }
        assertTrue(File(source, paths.first()).readText().contains("LettaListRow("))
        val rowFiles = source.walkTopDown().filter { it.isFile && it.name.endsWith("Row.kt") }
        for (file in rowFiles) {
            assertEquals(0, Regex("collectIsHoveredAsState\\s*\\(").findAll(file.readText()).count(), file.path)
        }
    }
}
