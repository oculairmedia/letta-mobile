package com.letta.mobile.ui.screens.config

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import ca.oculair.meridian.R
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.LettaDimens

// The settings screen's appearance card, and the haptic switch its rows (and the other cards) use.

@Composable
internal fun AppearanceSection(
    state: ConfigUiState,
    callbacks: AppearanceCallbacks,
) {
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    CardGroup(title = {
        ConfigSectionTitle(stringResource(R.string.screen_config_appearance_section))
    }) {
        item(
            headlineContent = {
                ThemeModeSelector(theme = state.theme, onThemeChange = callbacks.onThemeChange)
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_config_dynamic_color)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (dynamicColorSupported) {
                            R.string.screen_config_dynamic_color_supported
                        } else {
                            R.string.screen_config_dynamic_color_unsupported
                        }
                    )
                )
            },
            trailingContent = {
                HapticSwitch(
                    checked = state.dynamicColor,
                    onCheckedChange = callbacks.onDynamicColorChange,
                    enabled = dynamicColorSupported,
                )
            },
        )
        item(
            headlineContent = {
                ThemePresetPicker(selected = state.themePreset, onThemePresetChange = callbacks.onThemePresetChange)
            },
            supportingContent = {
                Text(
                    stringResource(
                        if (state.dynamicColor && dynamicColorSupported) {
                            R.string.screen_config_theme_preset_overridden
                        } else {
                            R.string.screen_config_theme_preset
                        }
                    )
                )
            },
        )
    }
}

@Composable
private fun ThemeModeSelector(
    theme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    FormItem(label = { Text(stringResource(R.string.screen_config_theme_mode)) }) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = theme == AppTheme.SYSTEM,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = theme != AppTheme.SYSTEM)
                    onThemeChange(AppTheme.SYSTEM)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                label = { Text(stringResource(R.string.screen_config_theme_mode_system)) },
            )
            SegmentedButton(
                selected = theme == AppTheme.LIGHT,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = theme != AppTheme.LIGHT)
                    onThemeChange(AppTheme.LIGHT)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                label = { Text(stringResource(R.string.common_light_theme)) },
            )
            SegmentedButton(
                selected = theme == AppTheme.DARK,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = theme != AppTheme.DARK)
                    onThemeChange(AppTheme.DARK)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                label = { Text(stringResource(R.string.common_dark_theme)) },
            )
        }
    }
}

@Composable
private fun ThemePresetPicker(
    selected: ThemePreset,
    onThemePresetChange: (ThemePreset) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ThemePreset.entries.forEach { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = selected != preset)
                    onThemePresetChange(preset)
                },
                label = { Text(themePresetLabel(preset)) },
            )
        }
    }
}

@Composable
internal fun HapticSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    Switch(
        checked = checked,
        enabled = enabled,
        modifier = modifier,
        onCheckedChange = { isChecked ->
            if (isChecked) {
                HapticEffects.toggleOn(haptic, view)
            } else {
                HapticEffects.toggleOff(haptic, view)
            }
            onCheckedChange(isChecked)
        },
    )
}

@Composable
private fun themePresetLabel(themePreset: ThemePreset): String {
    return when (themePreset) {
        ThemePreset.DEFAULT -> stringResource(R.string.screen_config_theme_preset_default)
        ThemePreset.OCEAN -> stringResource(R.string.screen_config_theme_preset_ocean)
        ThemePreset.AMOLED_BLACK -> stringResource(R.string.screen_config_theme_preset_amoled_black)
        ThemePreset.SAKURA -> stringResource(R.string.screen_config_theme_preset_sakura)
        ThemePreset.AUTUMN -> stringResource(R.string.screen_config_theme_preset_autumn)
        ThemePreset.SPRING -> stringResource(R.string.screen_config_theme_preset_spring)
    }
}
