package com.letta.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.letta.mobile.ui.theme.LettaDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wires [DisclosureChevron] through composition. The pure-function tests live
 * in sharedUI; this one checks the branches those tests never reach: caller
 * test tags, the sheet rotation bypass, the content-description override, and
 * that size, tint, and rotation land on the icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DisclosureChevronComposableTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun callerTestTagReplacesTheDefaultTag() {
        composeRule.setContent {
            DisclosureChevron(
                expanded = false,
                modifier = Modifier.testTag("run_activity_chevron"),
            )
        }

        composeRule.onNodeWithTag("run_activity_chevron").assertExists()
        assertTrue(
            composeRule.onAllNodesWithTag(DisclosureChevronDefaults.TestTag).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun defaultTestTagRemainsWhenTheCallerSetsNone() {
        composeRule.setContent {
            DisclosureChevron(expanded = false)
        }

        composeRule.onNodeWithTag(DisclosureChevronDefaults.TestTag).assertExists()
    }

    @Test
    fun sheetStaysCollapsedWhileAnExpandedChevronIsRotated() {
        var indicates by mutableStateOf(ChevronIndication.Expansion)
        composeRule.setContent {
            DisclosureChevron(expanded = true, indicates = indicates)
        }
        assertEquals(
            DisclosureChevronDefaults.RotationExpanded,
            composeRule.onNodeWithTag(DisclosureChevronDefaults.TestTag).rotationZ(),
            0.5f,
        )

        composeRule.runOnIdle { indicates = ChevronIndication.Sheet }
        assertEquals(
            DisclosureChevronDefaults.RotationCollapsed,
            composeRule.onNodeWithTag(DisclosureChevronDefaults.TestTag).rotationZ(),
            0.5f,
        )
    }

    @Test
    fun contentDescriptionOverrideReplacesTheDerivedLabel() {
        composeRule.setContent {
            DisclosureChevron(
                expanded = true,
                indicates = ChevronIndication.Sheet,
                contentDescription = "Expand details",
            )
        }

        composeRule.onNodeWithTag(DisclosureChevronDefaults.TestTag)
            .assertContentDescriptionEquals("Expand details")
    }

    @Test
    fun omittedContentDescriptionUsesTheDerivedLabel() {
        composeRule.setContent {
            DisclosureChevron(expanded = false, indicates = ChevronIndication.Expansion)
        }

        composeRule.onNodeWithTag(DisclosureChevronDefaults.TestTag)
            .assertContentDescriptionEquals("Expand")
    }

    @Test
    fun sizeTintAndRotationAreAppliedToTheIcon() {
        val onSurfaceVariant = Color(0xFF5C5C5C)
        val primary = Color(0xFF1565C0)
        val scheme = lightColorScheme(
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
        )
        val expectedTint = DisclosureChevronDefaults.tint(
            enabled = true,
            emphasis = ChevronEmphasis.Emphasized,
            onSurfaceVariant = scheme.onSurfaceVariant,
            primary = scheme.primary,
        )

        composeRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                DisclosureChevron(
                    expanded = true,
                    emphasis = ChevronEmphasis.Emphasized,
                    compact = true,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(DisclosureChevronDefaults.AnimationDurationMillis.toLong())

        val icon = composeRule.onNodeWithTag(DisclosureChevronDefaults.TestTag)
        icon.assertWidthIsEqualTo(LettaDimens.Control.iconSm)
        assertEquals(DisclosureChevronDefaults.RotationExpanded, icon.rotationZ(), 0.5f)
        val applied = icon.appliedTint()
        assertNotNull("Icon painter had no tint", applied)
        assertEquals(expectedTint.red, applied!!.red, 0.02f)
        assertEquals(expectedTint.green, applied.green, 0.02f)
        assertEquals(expectedTint.blue, applied.blue, 0.02f)
        assertEquals(expectedTint.alpha, applied.alpha, 0.02f)
    }
}

private fun SemanticsNodeInteraction.rotationZ(): Float {
    val layers = fetchSemanticsNode().layoutInfo.getModifierInfo()
        .map { it.modifier }
        .filter { it.javaClass.name.endsWith("GraphicsLayerElement") }
    if (layers.isEmpty()) return 0f
    return layers.maxOf { layer ->
        layer.javaClass.getMethod("getRotationZ").accessible().invoke(layer) as Float
    }
}

private fun SemanticsNodeInteraction.appliedTint(): Color? {
    val colors = fetchSemanticsNode().layoutInfo.getModifierInfo().mapNotNull { info ->
        val getter = info.modifier.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name == "getColorFilter"
        }?.accessible() ?: return@mapNotNull null
        val filter = getter.invoke(info.modifier) ?: return@mapNotNull null
        if (!filter.javaClass.name.endsWith("BlendModeColorFilter")) return@mapNotNull null
        val packed = filter.javaClass.getMethod("getColor-0d7_KjU").accessible().invoke(filter) as Long
        // The value-class bits keep sRGB in the high 32. Color(Long) expects an
        // ARGB int in the low 32 and would read this as transparent.
        Color((packed ushr 32).toInt())
    }
    return colors.lastOrNull { it.alpha > 0f } ?: colors.lastOrNull()
}

private fun java.lang.reflect.Method.accessible(): java.lang.reflect.Method = apply { isAccessible = true }
