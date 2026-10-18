package com.letta.mobile.desktop

import com.letta.mobile.ui.theme.jvmOsReducedMotion

/**
 * Whether the OS has UI animations disabled. Best-effort: AWT's
 * `win.uiEffects.animationsEnabled` desktop property reflects the Windows
 * "Show animations" accessibility setting; other platforms — and any
 * environment where the property is unavailable — fall back to animating
 * normally rather than guessing. `-Dletta.reducedMotion=true` is available
 * as an explicit override for tests/screenshots.
 */
internal fun desktopPrefersReducedMotion(): Boolean = jvmOsReducedMotion()
