package com.letta.mobile.feature.chat.zoom

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import com.letta.mobile.ui.theme.scaledBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-tgypm.3. The timeline scales the whole Material set rather than each call site, so
 * this asserts the set is actually whole: every TextStyle Material exposes has to come back larger.
 *
 * Reflective on purpose. A Material upgrade that adds a style would otherwise leave one silently
 * unscaled, and the text that used it would stop following the pinch with nothing to notice it.
 */
class TimelineTypographyCoverageTest {

    private fun styleGetters() = Typography::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == TextStyle::class.java && it.name.startsWith("get") }
        .sortedBy { it.name }

    @Test fun everyMaterialTextStyleIsScaled() {
        val base = Typography()
        val scaled = base.scaledBy(2f)
        val getters = styleGetters()
        assertTrue("found no TextStyle getters to check", getters.size >= 15)
        val unscaled = getters.filter { getter ->
            val from = (getter.invoke(base) as TextStyle).fontSize.value
            val to = (getter.invoke(scaled) as TextStyle).fontSize.value
            from.isFinite() && from > 0f && to != from * 2f
        }.map { it.name.removePrefix("get") }
        assertEquals("these Material styles are not scaled by Typography.scaledBy", emptyList<String>(), unscaled)
    }

    @Test fun scalingByOneIsIdentity() {
        val base = Typography()
        assertTrue("a no-op scale should not allocate a new set", base.scaledBy(1f) === base)
    }

    /** The derived styles in TypeHierarchy read from the set, so they follow without their own rule. */
    @Test fun derivedStylesFollowTheScaledSet() {
        val base = Typography()
        val scaled = base.scaledBy(2f)
        assertEquals(
            base.labelMediumEmphasized.fontSize.value * 2f,
            scaled.labelMediumEmphasized.fontSize.value,
            0.01f,
        )
        assertEquals(base.titleSmallEmphasized.fontSize.value * 2f, scaled.titleSmallEmphasized.fontSize.value, 0.01f)
        assertEquals(base.bodySmall.fontSize.value * 2f, scaled.bodySmall.fontSize.value, 0.01f)
    }
}
