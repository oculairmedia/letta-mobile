package com.letta.mobile.ui.components

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTrailingActionRetentionTest {
    @Test
    fun `voice exit retains custom content instead of flashing send`() {
        val source = Files.readString(
            Paths.get("src/main/java/com/letta/mobile/ui/components/LettaInputBar.kt"),
        )

        assertTrue(source.contains("var retainedCustomContent by remember"))
        assertTrue(source.contains("val customContent = if (spec.visible) spec.customContent else retainedCustomContent"))
        assertTrue(source.contains("customContent?.let"))
        assertTrue(source.contains("targetScale = 0.76f"))
    }
}
