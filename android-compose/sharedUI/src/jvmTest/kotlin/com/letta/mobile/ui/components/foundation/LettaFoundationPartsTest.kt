@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedShapes
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaStatusTone
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LettaFoundationPartsTest {
    @Test
    fun aStatusDotIsOneSizeAndEachToneOneDistinctColour() = runDesktopComposeUiTest {
        val colours = mutableMapOf<LettaStatusTone, Color>()
        setFoundationContent {
            Row {
                LettaStatusTone.entries.forEach { tone ->
                    colours[tone] = tone.color()
                    LettaStatusDot(tone, Modifier.testTag(tone.name))
                }
            }
        }
        LettaStatusTone.entries.forEach {
            onNodeWithTag(it.name).assertWidthIsEqualTo(LettaComponentDimens.statusDot).assertHeightIsEqualTo(LettaComponentDimens.statusDot)
        }
        assertEquals(LettaStatusTone.entries.size, colours.values.distinct().size, "tones share a colour: $colours")
    }

    @Test
    fun aStatusLabelCarriesItsWords() = runDesktopComposeUiTest {
        setFoundationContent { LettaStatusLabel("Syncing", LettaStatusTone.Running) }
        onNodeWithText("Syncing").assertExists()
    }

    @Test
    fun aSwatchIsARadioChoiceWithAFullTouchTarget() = runDesktopComposeUiTest {
        var picked = 0
        setFoundationContent {
            LettaColorSwatch(Color.Red, selected = true, modifier = Modifier.testTag(SWATCH), contentDescription = "Color red", onClick = { picked++ })
        }
        onNodeWithTag(SWATCH)
            .assert(hasRole(Role.RadioButton))
            .assertIsSelected()
            .assertWidthIsEqualTo(LettaComponentDimens.swatch)
            .assertTouchHeightIsEqualTo(LettaControlTokens.minTouchTarget)
            .performClick()
        onNodeWithContentDescription("Color red").assertExists()
        assertEquals(1, picked)
    }

    @Test
    fun anEmptyStateCentresItsWordsAndAction() {
        FOUNDATION_WIDTHS.forEach { width ->
            runDesktopComposeUiTest {
                var acted = false
                setFoundationContent(width) {
                    LettaEmptyState(
                        text = "Use New schedule to create one.",
                        modifier = Modifier.testTag(EMPTY),
                        title = "No schedules yet",
                        action = { TextButton(onClick = { acted = true }) { Text("New schedule") } },
                    )
                }
                onNodeWithTag(EMPTY).assertWidthIsEqualTo(width)
                val words = onNodeWithText("No schedules yet").getUnclippedBoundsInRoot()
                val centre = (words.left + words.right) / 2
                assertTrue((centre - width / 2).value in -1f..1f, "at $width the title is centred at $centre")
                onNodeWithText("New schedule").performClick()
                assertTrue(acted)
            }
        }
    }

    @Test
    fun aNavChevronIsADecorativeGlyphAtTheScale() = runDesktopComposeUiTest {
        setFoundationContent { LettaNavChevron(Modifier.testTag(CHEVRON)) }
        onNodeWithTag(CHEVRON).assertWidthIsEqualTo(LettaControlTokens.icon).assertHeightIsEqualTo(LettaControlTokens.icon)
    }

    @Test
    fun dividersAreOneHairline() = runDesktopComposeUiTest {
        setFoundationContent(FOUNDATION_WIDTHS.last()) {
            Column {
                LettaDivider(Modifier.testTag(HORIZONTAL))
                Row(Modifier.height(LettaControlTokens.minTouchTarget)) { LettaVerticalDivider(Modifier.fillMaxHeight().testTag(VERTICAL)) }
            }
        }
        onNodeWithTag(HORIZONTAL).assertHeightIsEqualTo(LettaStrokeTokens.hairline).assertWidthIsEqualTo(FOUNDATION_WIDTHS.last())
        onNodeWithTag(VERTICAL).assertWidthIsEqualTo(LettaStrokeTokens.hairline).assertHeightIsEqualTo(LettaControlTokens.minTouchTarget)
    }

    @Test
    fun aClickableRowAnnouncesItsRoleAndIsATouchTargetTall() = runDesktopComposeUiTest {
        var clicks = 0
        setFoundationContent {
            Text("Runs", Modifier.testTag(ROW).lettaClickable(role = Role.Tab, onClickLabel = "Open runs") { clicks++ })
        }
        onNodeWithTag(ROW).assert(hasRole(Role.Tab)).assert(hasClickAction()).assertHeightIsEqualTo(LettaControlTokens.minTouchTarget).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun theOneCornerScaleIsTheRadiusScale() {
        val density = Density(1f)
        fun Shape.corner(): Float {
            val outline = createOutline(Size(SIDE, SIDE), LayoutDirection.Ltr, density)
            return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
        }
        assertEquals(LettaDimens.Radius.sm.value, SharedShapes.small.corner())
        assertEquals(LettaDimens.Radius.md.value, SharedShapes.medium.corner())
        assertEquals(LettaDimens.Radius.lg.value, SharedShapes.large.corner())
        assertNotEquals(SharedShapes.small.corner(), SharedShapes.medium.corner())
    }

    private companion object {
        const val SWATCH = "swatch"
        const val EMPTY = "empty"
        const val CHEVRON = "chevron"
        const val HORIZONTAL = "horizontal"
        const val VERTICAL = "vertical"
        const val ROW = "row"
        const val SIDE = 100f
    }
}
