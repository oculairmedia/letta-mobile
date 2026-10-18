package com.letta.mobile.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberOsReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { androidReducedMotion(context) }
}

@Composable
internal actual fun rememberOsHighContrast(): Boolean {
    val context = LocalContext.current
    return remember(context) { androidHighContrast(context) }
}

internal fun androidReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

internal fun androidHighContrast(context: Context): Boolean = runCatching {
    Settings.Secure.getInt(context.contentResolver, HIGH_TEXT_CONTRAST, 0) == 1
}.getOrDefault(false)

private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"
