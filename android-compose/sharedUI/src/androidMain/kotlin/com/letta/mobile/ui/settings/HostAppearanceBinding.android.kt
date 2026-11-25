package com.letta.mobile.ui.settings

import android.os.Build

/** Wallpaper colours exist from Android 12, and the dynamic-colour row draws only there. */
actual fun wallpaperColoursSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
