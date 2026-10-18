package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit

@Composable
internal actual fun rememberOsReducedMotion(): Boolean = remember { jvmOsReducedMotion() }

@Composable
internal actual fun rememberOsHighContrast(): Boolean = remember { jvmOsHighContrast() }

/** Desktop/OS reduced motion, plus `-Dletta.reducedMotion=true` for tests and screenshots. */
fun jvmOsReducedMotion(): Boolean {
    if (System.getProperty(REDUCED_MOTION_PROP) == "true") return true
    return awtFlag(AWT_ANIMATIONS_ENABLED)?.not() ?: false
}

/** Desktop/OS high contrast, plus `-Dletta.highContrast=true` for tests. */
fun jvmOsHighContrast(): Boolean {
    if (System.getProperty(HIGH_CONTRAST_PROP) == "true") return true
    return awtFlag(AWT_HIGH_CONTRAST) ?: false
}

private fun awtFlag(key: String): Boolean? = runCatching {
    Toolkit.getDefaultToolkit().getDesktopProperty(key) as? Boolean
}.getOrNull()

private const val REDUCED_MOTION_PROP = "letta.reducedMotion"
private const val HIGH_CONTRAST_PROP = "letta.highContrast"
private const val AWT_ANIMATIONS_ENABLED = "win.uiEffects.animationsEnabled"
private const val AWT_HIGH_CONTRAST = "win.highContrast.on"
