package com.letta.mobile.ui.components

import androidx.compose.runtime.Composable
import com.letta.mobile.ui.theme.rememberReducedMotionEnabled as themeReducedMotion

/**
 * letta-mobile-k5nbq.1: the Android designsystem copy lived here; hosts now publish the OS
 * flag through [com.letta.mobile.ui.theme.ProvideAccessibilityPolicy]. This name stays so
 * existing Android callers keep compiling.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean = themeReducedMotion()
