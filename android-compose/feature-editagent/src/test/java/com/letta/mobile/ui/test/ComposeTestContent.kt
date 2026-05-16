package com.letta.mobile.ui.test

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.LocalWindowSizeClass

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
private val DefaultTestWindowSizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 640.dp))

fun ComposeContentTestRule.setLettaTestContent(
    appTheme: AppTheme = AppTheme.LIGHT,
    themePreset: ThemePreset = ThemePreset.DEFAULT,
    dynamicColor: Boolean = false,
    useChatTheme: Boolean = true,
    windowSizeClass: WindowSizeClass = DefaultTestWindowSizeClass,
    content: @Composable () -> Unit,
) {
    setContent {
        CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
            LettaTheme(
                appTheme = appTheme,
                themePreset = themePreset,
                dynamicColor = dynamicColor,
            ) {
                if (useChatTheme) {
                    LettaChatTheme {
                        content()
                    }
                } else {
                    content()
                }
            }
        }
    }
}
