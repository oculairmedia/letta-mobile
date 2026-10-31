@file:OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)

package com.letta.mobile.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.pill.LettaPillColors
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The theme's shared primitives: section label and header, the pill family, spinner and busy bar (letta-mobile-yx00h). */
class LettaPrimitivesTest {
    @Test
    fun sectionHeaderWritesItsCountAndIsAHeading() = runDesktopComposeUiTest {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                LettaSectionHeader("Tools", count = 12, modifier = Modifier.testTag(HEADER)) { IconButton(onClick = {}) { Text("+") } }
            }
        }
        onNodeWithTag(HEADER).assert(isHeading())
        onNodeWithText("TOOLS · 12").assertExists()
        assertEquals("Tools", lettaSectionTitle("Tools", null))
    }

    @Test
    fun everyPillVariantSharesOneHeightAndTheChipKeepsATouchTarget() = runDesktopComposeUiTest {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                Column {
                    LettaPill("tag", MaterialTheme.colorScheme.secondary, Modifier.testTag("tinted"))
                    LettaTonalPill(
                        "Active",
                        LettaPillColors(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer),
                        Modifier.testTag("tonal"),
                    )
                    LettaOutlinedPill("Read only", Modifier.testTag("outlined"))
                    LettaPill("fault", MaterialTheme.colorScheme.error, Modifier.testTag("icon"), icon = LettaIcons.Check)
                    // The box measures the chip's layout, touch target included.
                    Box(Modifier.testTag("chip")) { LettaChip("High", onClick = {}, trailingIcon = LettaIcons.ChevronRight) }
                }
            }
        }
        val heights = listOf("tinted", "tonal", "outlined", "icon").map { onNodeWithTag(it).getUnclippedBoundsInRoot().height }
        assertEquals(1, heights.distinct().size, "one pill height across the family: $heights")
        val chip = onNodeWithTag("chip").getUnclippedBoundsInRoot()
        assertTrue(chip.height >= LettaDimens.Control.minTouchTarget, "the chip's touch target is ${chip.height}")
    }

    @Test
    fun theLargeSpinnerIsMaterialsOwnSize() = runDesktopComposeUiTest {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                Column {
                    CircularProgressIndicator(Modifier.testTag("material"))
                    LettaSpinner(Modifier.testTag("large"), size = LettaDimens.Control.spinnerLg)
                    LettaSpinner(Modifier.testTag("small"), size = LettaDimens.Control.spinnerSm)
                }
            }
        }
        val material = onNodeWithTag("material").getUnclippedBoundsInRoot()
        assertEquals(material.width, onNodeWithTag("large").getUnclippedBoundsInRoot().width, "Lg draws exactly as the default did")
        assertEquals(LettaDimens.Control.iconSm, onNodeWithTag("small").getUnclippedBoundsInRoot().width)
    }

    /** Every primitive on one surface at the four reference widths, for review under build/design-system-render/. */
    @Test
    fun galleryRendersAtEveryReferenceWidth() = listOf(360, 700, 760, 1200).forEach { width ->
        runDesktopComposeUiTest(width = width, height = GALLERY_HEIGHT) {
            setContent { SharedMaterialTheme(darkTheme = true) { Gallery() } }
            waitForIdle()
            val out = File("build/design-system-render").apply { mkdirs() }.resolve("gallery-$width.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            assertTrue(out.length() > 0, "rendered ${out.name}")
            val label = onNodeWithText("PILLS · 5").getUnclippedBoundsInRoot()
            assertTrue(label.right.value <= width, "the header fits a $width dp window")
        }
    }

    @androidx.compose.runtime.Composable
    private fun Gallery() {
        val scheme = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(Modifier.padding(LettaDimens.Space.lg), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                LettaSectionHeader("Pills", count = 5) { IconButton(onClick = {}) { Text("+") } }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                    verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                ) {
                    LettaPill("git", scheme.secondary)
                    LettaPill("v1.2.0", scheme.tertiary)
                    LettaPill("Fault", scheme.error, icon = LettaIcons.Check)
                    LettaTonalPill("Active", LettaPillColors(scheme.tertiaryContainer, scheme.onTertiaryContainer))
                    LettaTonalPill("Paused", LettaPillColors(scheme.secondaryContainer, scheme.onSecondaryContainer))
                    LettaTonalPill("12 / 80", LettaPillColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant))
                    LettaOutlinedPill("Full access")
                    LettaChip("High", onClick = {}, trailingIcon = LettaIcons.ChevronRight)
                }
                LettaSectionLabel("Status")
                LettaStatusLabel("Connected", scheme.primary, icon = LettaIcons.Check)
                LettaStatusLabel("Syncing", scheme.onSurfaceVariant)
                LettaSectionLabel("Progress")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
                    LettaSpinner(size = LettaDimens.Control.spinnerSm)
                    LettaSpinner()
                    LettaSpinner(size = LettaDimens.Control.spinnerLg)
                }
                LettaBusyBar()
                LettaBusyBar(progress = { GALLERY_PROGRESS })
            }
        }
    }

    private companion object {
        const val HEADER = "header"
        const val GALLERY_HEIGHT = 420
        const val GALLERY_PROGRESS = 0.4f
    }
}
