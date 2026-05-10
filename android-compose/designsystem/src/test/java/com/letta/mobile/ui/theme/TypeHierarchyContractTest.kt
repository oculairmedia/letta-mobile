package com.letta.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

@Tag("unit")
class TypeHierarchyContractTest {

    private val typography = Typography()

    @Test
    fun `list item hierarchy maps to semantic Material roles`() {
        assertEquals(typography.titleMedium, typography.listItemHeadline)
        assertEquals(typography.bodySmall, typography.listItemSupporting)
        assertEquals(typography.labelMedium, typography.listItemMetadata)
    }

    @Test
    fun `metadata monospace keeps label sizing with monospace family`() {
        val style = typography.listItemMetadataMonospace

        assertEquals(typography.labelMedium.fontSize, style.fontSize)
        assertEquals(FontFamily.Monospace, style.fontFamily)
    }

    @Test
    fun `section and chat semantic roles remain emphasized`() {
        assertEquals(typography.titleSmallEmphasized, typography.sectionTitle)
        assertEquals(typography.labelMediumEmphasized, typography.chatBubbleSender)
        assertEquals(typography.headlineMedium, typography.statValue)
    }
}
