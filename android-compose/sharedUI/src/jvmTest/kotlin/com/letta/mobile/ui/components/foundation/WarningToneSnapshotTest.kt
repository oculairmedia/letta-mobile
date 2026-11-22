@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaColorTokens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.theme.tokens.LettaStatusTone
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Warning reads as a warning: the shared theme's warning ink and container are the amber tokens (not the
 * teal tertiary), and the warning notice, status label and plain warning text draw in them. Renders the
 * three in both themes at the phone, tablet and desktop widths to build/warning-tone-snapshots.
 */
class WarningToneSnapshotTest {
    @Test
    fun theSharedThemesWarningIsTheAmberTokens() = runComposeUiTest {
        val read = mutableMapOf<Boolean, List<Color>>()
        setContent {
            for (dark in listOf(false, true)) {
                SharedMaterialTheme(darkTheme = dark) {
                    read[dark] = listOf(
                        MaterialTheme.customColors.warningTextColor,
                        LettaPanelTone.Warning.containerColor(),
                        LettaPanelTone.Warning.contentColor(),
                        LettaStatusTone.Warning.color(),
                    )
                }
            }
        }
        waitForIdle()
        val light = Color(LettaColorTokens.LIGHT_WARNING)
        val dark = Color(LettaColorTokens.DARK_WARNING)
        assertEquals(listOf(light, Color(LettaColorTokens.LIGHT_WARNING_CONTAINER), light, light), read[false])
        assertEquals(listOf(dark, Color(LettaColorTokens.DARK_WARNING_CONTAINER), dark, dark), read[true])
    }

    @Composable
    private fun WarningSurfaces() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LettaInlineNotice(LettaNotice("This folder is a worktree; changes land on its branch.", tone = LettaPanelTone.Warning))
            LettaStatusLabel("No key", LettaStatusTone.Warning)
            Text("Unsaved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.warningTextColor)
        }
    }

    private fun snapshot(width: Dp, dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Column(Modifier.width(width).background(MaterialTheme.colorScheme.background).padding(16.dp).testTag(FRAME)) {
                    WarningSurfaces()
                }
            }
        }
        waitForIdle()
        val out = File("build/warning-tone-snapshots").apply { mkdirs() }
            .resolve("warning-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun warningSurfacesAtEveryWidth() = WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(width, it) } }

    private companion object {
        const val FRAME = "warning-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 400
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
