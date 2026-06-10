package com.letta.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

@Tag("unit")
class TypeHierarchyContractTest {

    private val typography = Typography()

    @Test
    fun `list item hierarchy maps to semantic Material roles`() {
        assertEquals(typography.titleMedium, typography.listItemHeadline)
        assertEquals(typography.bodySmall.copy(fontWeight = FontWeight.Medium), typography.listItemSupporting)
        assertEquals(typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), typography.listItemMetadata)
    }

    @Test
    fun `metadata monospace keeps label sizing with monospace family`() {
        val style = typography.listItemMetadataMonospace

        assertEquals(typography.labelMedium.fontSize, style.fontSize)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
        assertEquals(LettaCodeFont, style.fontFamily)
    }

    @Test
    fun `section and chat semantic roles remain emphasized`() {
        assertEquals(typography.titleSmallEmphasized, typography.sectionTitle)
        assertEquals(typography.labelMediumEmphasized, typography.chatBubbleSender)
        assertEquals(typography.headlineMedium, typography.statValue)
    }

    @Test
    fun `editorial body prose carries loose rhythm and OpenType features`() {
        // docs/design/editorial-prose.md §1: chat prose body roles get generous
        // line height, opened tracking, ligatures, hyphenation, and balanced
        // paragraph line breaking. Asserts against the repo's Typography val
        // (not the M3 default).
        val medium = Typography.bodyMedium
        assertEquals(24.sp, medium.lineHeight)
        assertEquals(0.3.sp, medium.letterSpacing)
        assertEquals("liga, calt", medium.fontFeatureSettings)
        assertEquals(Hyphens.Auto, medium.hyphens)
        assertEquals(LineBreak.Paragraph, medium.lineBreak)

        val large = Typography.bodyLarge
        assertEquals(26.sp, large.lineHeight)
        assertEquals("liga, calt", large.fontFeatureSettings)
        // Line height must stay looser than the old 1.43/1.5 ratios.
        assertTrue(medium.lineHeight.value > medium.fontSize.value * 1.6f)
    }
}
