package com.letta.mobile.ui.theme.tokens

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient high-contrast preference for shared Compose UI. True keeps the edges a high-contrast reader
 * leans on strong: the panel hairline (lettaPanelBorder) carries the whole theme-boosted
 * `outlineVariant` instead of its normal whisper.
 *
 * ProvideAccessibilityPolicy at each host theme fills this from the person's stored choice or the OS;
 * tests may still override it. It sits with the leaf tokens so the shared components in
 * ui.components.foundation can honour it without importing ui.theme.
 */
val LocalLettaHighContrast: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
