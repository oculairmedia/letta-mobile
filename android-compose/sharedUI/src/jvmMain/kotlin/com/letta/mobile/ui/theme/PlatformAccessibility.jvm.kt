package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Toolkit
import java.beans.PropertyChangeListener
import kotlinx.coroutines.delay

@Composable
internal actual fun rememberOsReducedMotion(): Boolean =
    rememberLiveDesktopFlag(AWT_ANIMATIONS_ENABLED, ::jvmOsReducedMotion)

@Composable
internal actual fun rememberOsHighContrast(): Boolean =
    rememberLiveDesktopFlag(AWT_HIGH_CONTRAST, ::jvmOsHighContrast)

@Composable
private fun rememberLiveDesktopFlag(awtProperty: String, read: () -> Boolean): Boolean {
    var value by remember { mutableStateOf(read()) }
    DisposableEffect(awtProperty) {
        val unsubscribe = observeAwtDesktopProperty(awtProperty) { value = read() }
        onDispose(unsubscribe)
    }
    if (isMacOs()) {
        LaunchedEffect(awtProperty) {
            while (true) {
                delay(MAC_POLL_INTERVAL_MS)
                value = read()
            }
        }
    }
    return value
}

internal fun observeAwtDesktopProperty(property: String, onChange: () -> Unit): () -> Unit {
    val listener = PropertyChangeListener { onChange() }
    val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull()
    toolkit?.addPropertyChangeListener(property, listener)
    return { toolkit?.removePropertyChangeListener(property, listener) }
}

/** Desktop/OS reduced motion, plus `-Dletta.reducedMotion=true` for tests and screenshots. */
fun jvmOsReducedMotion(): Boolean {
    if (System.getProperty(REDUCED_MOTION_PROP) == "true") return true
    if (isMacOs()) return macWorkspaceFlag(MAC_REDUCE_MOTION_SEL) ?: false
    return awtFlag(AWT_ANIMATIONS_ENABLED)?.not() ?: false
}

/** Desktop/OS high contrast, plus `-Dletta.highContrast=true` for tests. */
fun jvmOsHighContrast(): Boolean {
    if (System.getProperty(HIGH_CONTRAST_PROP) == "true") return true
    if (isMacOs()) return macWorkspaceFlag(MAC_INCREASE_CONTRAST_SEL) ?: false
    return awtFlag(AWT_HIGH_CONTRAST) ?: false
}

internal fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.contains("Mac", ignoreCase = true) || osName.contains("Darwin", ignoreCase = true)

internal fun macWorkspaceFlag(selectorName: String): Boolean? = runCatching {
    val objc = objcRuntime ?: return null
    val workspaceClass = objc.objc_getClass("NSWorkspace") ?: return null
    val sharedSel = objc.sel_registerName("sharedWorkspace") ?: return null
    val workspace = objc.objc_msgSend(workspaceClass, sharedSel) ?: return null
    val flagSel = objc.sel_registerName(selectorName) ?: return null
    val raw = objc.objc_msgSend(workspace, flagSel)
    (Pointer.nativeValue(raw) and 0xFFL) != 0L
}.getOrNull()

@Suppress("FunctionNaming")
private interface ObjCRuntime : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer?
    fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
}

private val objcRuntime: ObjCRuntime? by lazy {
    if (!isMacOs()) null else runCatching { Native.load("objc", ObjCRuntime::class.java) }.getOrNull()
}

private fun awtFlag(key: String): Boolean? = runCatching {
    Toolkit.getDefaultToolkit().getDesktopProperty(key) as? Boolean
}.getOrNull()

private const val REDUCED_MOTION_PROP = "letta.reducedMotion"
private const val HIGH_CONTRAST_PROP = "letta.highContrast"
private const val AWT_ANIMATIONS_ENABLED = "win.uiEffects.animationsEnabled"
private const val AWT_HIGH_CONTRAST = "win.highContrast.on"
internal const val MAC_REDUCE_MOTION_SEL = "accessibilityDisplayShouldReduceMotion"
internal const val MAC_INCREASE_CONTRAST_SEL = "accessibilityDisplayShouldIncreaseContrast"
private const val MAC_POLL_INTERVAL_MS = 2_000L
