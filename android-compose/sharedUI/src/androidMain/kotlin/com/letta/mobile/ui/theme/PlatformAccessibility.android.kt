package com.letta.mobile.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberOsReducedMotion(): Boolean {
    val context = LocalContext.current
    return rememberAndroidSetting(
        context = context,
        uri = runCatching { Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrNull(),
        read = ::androidReducedMotion,
    )
}

@Composable
internal actual fun rememberOsHighContrast(): Boolean {
    val context = LocalContext.current
    return rememberAndroidSetting(
        context = context,
        uri = runCatching { Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST) }.getOrNull(),
        read = ::androidHighContrast,
    )
}

@Composable
private fun rememberAndroidSetting(
    context: Context,
    uri: Uri?,
    read: (Context) -> Boolean,
): Boolean {
    var value by remember(context) { mutableStateOf(read(context)) }
    DisposableEffect(context, uri) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = read(context)
            }
        }
        if (uri != null) {
            runCatching { resolver.registerContentObserver(uri, false, observer) }
        }
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return value
}

internal fun androidReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

internal fun androidHighContrast(context: Context): Boolean = runCatching {
    Settings.Secure.getInt(context.contentResolver, HIGH_TEXT_CONTRAST, 0) == 1
}.getOrDefault(false)

private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"
