package com.letta.mobile.ui.components

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisclosureChevronTest {

    private val onSurfaceVariant = Color(0xFF49454F)
    private val primary = Color(0xFF6750A4)

    @Test
    fun `rotation targets for expansion and sheet modes`() {
        assertEquals(
            0f,
            DisclosureChevronDefaults.targetRotation(
                indicates = ChevronIndication.Expansion,
                expanded = false,
            ),
        )
        assertEquals(
            180f,
            DisclosureChevronDefaults.targetRotation(
                indicates = ChevronIndication.Expansion,
                expanded = true,
            ),
        )
        assertEquals(
            0f,
            DisclosureChevronDefaults.targetRotation(
                indicates = ChevronIndication.Sheet,
                expanded = false,
            ),
        )
        assertEquals(
            0f,
            DisclosureChevronDefaults.targetRotation(
                indicates = ChevronIndication.Sheet,
                expanded = true,
            ),
        )
    }

    @Test
    fun `tint neutral yields onSurfaceVariant alpha 0_8 when enabled`() {
        val tint = DisclosureChevronDefaults.tint(
            enabled = true,
            emphasis = ChevronEmphasis.Neutral,
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
        )
        assertEquals(onSurfaceVariant.copy(alpha = 0.8f), tint)
    }

    @Test
    fun `tint emphasized yields primary when enabled`() {
        val tint = DisclosureChevronDefaults.tint(
            enabled = true,
            emphasis = ChevronEmphasis.Emphasized,
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
        )
        assertEquals(primary, tint)
    }

    @Test
    fun `tint when disabled overrides emphasis to onSurfaceVariant alpha 0_4`() {
        val neutralDisabled = DisclosureChevronDefaults.tint(
            enabled = false,
            emphasis = ChevronEmphasis.Neutral,
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
        )
        val emphasizedDisabled = DisclosureChevronDefaults.tint(
            enabled = false,
            emphasis = ChevronEmphasis.Emphasized,
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
        )
        val expected = onSurfaceVariant.copy(alpha = 0.4f)
        assertEquals(expected, neutralDisabled)
        assertEquals(expected, emphasizedDisabled)
    }

    @Test
    fun `compact sizing maps to iconSm while standard maps to icon`() {
        assertEquals(LettaDimens.Control.icon, DisclosureChevronDefaults.size(compact = false))
        assertEquals(LettaDimens.Control.iconSm, DisclosureChevronDefaults.size(compact = true))
    }

    @Test
    fun `derived content description resolves per indication and state`() {
        assertEquals(
            "Expand",
            DisclosureChevronDefaults.contentDescription(
                indicates = ChevronIndication.Expansion,
                expanded = false,
            ),
        )
        assertEquals(
            "Collapse",
            DisclosureChevronDefaults.contentDescription(
                indicates = ChevronIndication.Expansion,
                expanded = true,
            ),
        )
        assertEquals(
            "Open details",
            DisclosureChevronDefaults.contentDescription(
                indicates = ChevronIndication.Sheet,
                expanded = false,
            ),
        )
        assertEquals(
            "Open details",
            DisclosureChevronDefaults.contentDescription(
                indicates = ChevronIndication.Sheet,
                expanded = true,
            ),
        )
    }

    @Test
    fun `reduced-motion snap is used when enabled or when indicates Sheet`() {
        val reducedMotionSpec = DisclosureChevronDefaults.animationSpec(
            indicates = ChevronIndication.Expansion,
            reducedMotion = true,
        )
        assertTrue(reducedMotionSpec is SnapSpec, "Expected SnapSpec under reduced motion")

        val sheetSpec = DisclosureChevronDefaults.animationSpec(
            indicates = ChevronIndication.Sheet,
            reducedMotion = false,
        )
        assertTrue(sheetSpec is SnapSpec, "Expected SnapSpec for Sheet indication")

        val normalExpansionSpec = DisclosureChevronDefaults.animationSpec(
            indicates = ChevronIndication.Expansion,
            reducedMotion = false,
        )
        assertTrue(normalExpansionSpec is TweenSpec, "Expected TweenSpec for normal expansion")
        assertEquals(DisclosureChevronDefaults.AnimationDurationMillis, normalExpansionSpec.durationMillis)
    }

    @Test
    fun `stable test tag is letta_disclosure_chevron`() {
        assertEquals("letta_disclosure_chevron", DisclosureChevronDefaults.TestTag)
    }
}
