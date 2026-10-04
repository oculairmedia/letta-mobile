package com.letta.mobile.ui.haptics

import androidx.compose.runtime.staticCompositionLocalOf

/** Composition-scoped [Haptics]; defaults to [NoHaptics] (desktop, wasm, previews, tests). */
val LocalHaptics = staticCompositionLocalOf<Haptics> { NoHaptics }
