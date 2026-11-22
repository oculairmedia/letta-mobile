@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.choice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.settings.AppearanceSettingsSection
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.settings.rememberHostAppearanceSettings
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The shared segmented control: the selected segment reads as selected, a pick reports its key, a
 * disabled segment cannot be picked, and an icon-only segment is still read by its label. The theme
 * row draws it on a phone too. Renders the Appearance section in both themes at the phone, tablet and
 * desktop widths to build/segmented-snapshots.
 */
class LettaSegmentedControlUiTest {
    private val segments = listOf(
        LettaSegment("SYSTEM", "System"),
        LettaSegment("LIGHT", "Light"),
        LettaSegment("DARK", "Dark", enabled = false),
    )

    @Test
    fun aPickReportsItsKeyAndTheSelectionFollows() = runComposeUiTest {
        var selected by mutableStateOf("SYSTEM")
        setContent {
            MaterialTheme {
                LettaSegmentedControl(segments, selected, onSelect = { selected = it }, tagPrefix = TAG)
            }
        }
        onNodeWithTag(TAG + "SYSTEM").assertIsSelected()
        onNodeWithTag(TAG + "LIGHT").assertIsNotSelected().performClick()
        waitForIdle()
        assertEquals("LIGHT", selected)
        onNodeWithTag(TAG + "LIGHT").assertIsSelected()
        onNodeWithTag(TAG + "DARK").assertIsNotEnabled()
    }

    @Test
    fun anIconOnlySegmentIsReadByItsLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LettaSegmentedControl(
                    listOf(LettaSegment("list", "List view", LettaIcons.Menu), LettaSegment("grid", "Grid view", LettaIcons.Apps)),
                    selected = "list",
                    onSelect = {},
                    iconOnly = true,
                    tagPrefix = TAG,
                )
            }
        }
        onNodeWithTag(TAG + "grid").assertContentDescriptionEquals("Grid view")
    }

    @Test
    fun theThemeRowIsSegmentedOnAPhoneToo() = runComposeUiTest {
        val host = HeldAppearance(AppearanceValues(theme = "LIGHT", themePreset = "DEFAULT"))
        setContent {
            SharedMaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides LettaHost(input = LettaInput.Touch)) {
                    AppearanceSettingsSection(rememberHostAppearanceSettings(host))
                }
            }
        }
        // A segment, not a list's menu item: it is on screen without opening anything.
        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-LIGHT").assertIsSelected()
        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-DARK").performClick()
        waitForIdle()
        assertEquals("DARK", host.held.value.theme)
    }

    private fun snapshot(width: Dp, dark: Boolean) = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val host = HeldAppearance(AppearanceValues(theme = "SYSTEM", themePreset = "DEFAULT"))
        val input = if (width < 700.dp) LettaInput.Touch else LettaInput.Pointer
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLettaHost provides LettaHost(input = input)) {
                    Column(
                        Modifier.width(width).background(MaterialTheme.colorScheme.background).padding(16.dp).testTag(FRAME),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AppearanceSettingsSection(rememberHostAppearanceSettings(host))
                        LettaSegmentedControl(
                            listOf(LettaSegment("list", "List", LettaIcons.Menu), LettaSegment("grid", "Grid", LettaIcons.Apps)),
                            selected = "grid",
                            onSelect = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val out = File("build/segmented-snapshots").apply { mkdirs() }
            .resolve("segmented-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun segmentsAtEveryWidth() = WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(width, it) } }

    /** The host's appearance port, holding what it was last told. */
    private class HeldAppearance(initial: AppearanceValues) : HostAppearance {
        val held = MutableStateFlow(initial)
        override val values = held
        override val current: AppearanceValues get() = held.value

        override suspend fun setTheme(name: String) = take { it.copy(theme = name) }
        override suspend fun setThemePreset(name: String) = take { it.copy(themePreset = name) }
        override suspend fun setDynamicColor(enabled: Boolean) = take { it.copy(dynamicColor = enabled) }
        override suspend fun selectThemePreset(name: String, dynamicColor: Boolean?) =
            take { it.copy(themePreset = name, dynamicColor = dynamicColor ?: it.dynamicColor) }
        override suspend fun run() = Unit
        override suspend fun clearLocal() = Unit

        private fun take(change: (AppearanceValues) -> AppearanceValues): ShellStateEditOutcome {
            held.value = change(held.value)
            return ShellStateEditOutcome.APPLIED
        }
    }

    private companion object {
        const val TAG = "segment-"
        const val FRAME = "segmented-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 700
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
