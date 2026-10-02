@file:OptIn(InternalComposeUiApi::class)

package com.letta.mobile.desktop.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.platform.PlatformWindowInsetsProviderNode
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/**
 * The phone's window insets in dp, read live: the status bar, the gesture bar and the keyboard
 * (already including the gesture bar beneath it while it shows, as Android reports the IME).
 */
@Stable
internal interface PhoneInsetsSource {
    val density: Density
    val statusBarDp: Float
    val navigationBarDp: Float

    /** The keyboard's inset from the screen's bottom edge; 0 when it is down. */
    val imeDp: Float
}

/**
 * The phone's insets as Compose Multiplatform's skiko targets read them.
 *
 * On desktop, `WindowInsets.ime`, `.navigationBars`, `.statusBars`, `.safeDrawing` and friends all
 * read [LocalPlatformWindowInsets], and the inset-padding modifiers (`imePadding()`,
 * `safeDrawingPadding()`) read the nearest [PlatformWindowInsetsProviderNode] above them. A real
 * desktop window reports zero for all of them, so the shared Touch code (TouchComposerBar,
 * TouchCanvasDock, CanvasKeyboardCamera, ChatComposerPanel.imePadding) sees no keyboard and no
 * system bars. [ProvidePhoneWindowInsets] installs this object at both seams, so that code reads
 * the simulated phone exactly as it reads a real one, without a change to sharedUI or Android.
 *
 * Every value is computed on read from [source], whose fields are snapshot state: compositions,
 * layouts and padding nodes that read an inset are invalidated when it moves (the keyboard's
 * animation), as on a device.
 *
 * The seam is `@InternalComposeUiApi` (CMP-9379 plans to replace it): a Compose bump may move it.
 * It is dev-only here, so a break shows up as a compile error in this file and nothing else.
 */
internal class PhoneWindowInsets(private val source: PhoneInsetsSource) : PlatformWindowInsets {
    private fun px(dp: Float): Int = (dp * source.density.density).roundToInt().coerceAtLeast(0)

    override val statusBars: PlatformInsets = PlatformInsets(getTop = { px(source.statusBarDp) })
    override val navigationBars: PlatformInsets = PlatformInsets(getBottom = { px(source.navigationBarDp) })
    override val systemBars: PlatformInsets = PlatformInsets(
        getTop = { px(source.statusBarDp) },
        getBottom = { px(source.navigationBarDp) },
    )
    override val ime: PlatformInsets = PlatformInsets(getBottom = { px(source.imeDp) })

    /** The camera sits in the status bar, as on a Pixel. */
    override val displayCutout: PlatformInsets = PlatformInsets(getTop = { px(source.statusBarDp) })
    override val tappableElement: PlatformInsets = systemBars
    override val systemGestures: PlatformInsets = PlatformInsets(getBottom = { px(source.navigationBarDp) })
    override val mandatorySystemGestures: PlatformInsets = systemGestures

    /** A view of these insets without the safe (system bar) insets, the keyboard, or both. */
    override fun excluding(safeInsets: Boolean, ime: Boolean): PlatformWindowInsets {
        val safe = if (safeInsets) NoWindowInsets else this
        val keyboard = if (ime) NoWindowInsets else this
        return MixedWindowInsets(safe = safe, keyboard = keyboard)
    }
}

/** Reports nothing: every inset is the interface's zero default. */
private object NoWindowInsets : PlatformWindowInsets

/** The system bars and gestures of [safe], with the keyboard of [keyboard]. */
private class MixedWindowInsets(safe: PlatformWindowInsets, keyboard: PlatformWindowInsets) : PlatformWindowInsets {
    override val statusBars = safe.statusBars
    override val navigationBars = safe.navigationBars
    override val systemBars = safe.systemBars
    override val displayCutout = safe.displayCutout
    override val tappableElement = safe.tappableElement
    override val systemGestures = safe.systemGestures
    override val mandatorySystemGestures = safe.mandatorySystemGestures
    override val ime = keyboard.ime
}

/**
 * Gives [content] the phone's [insets]: through [LocalPlatformWindowInsets] for code that reads
 * `WindowInsets.*`, and through a provider node for the inset-padding modifiers.
 */
@Composable
internal fun ProvidePhoneWindowInsets(
    insets: PlatformWindowInsets,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.then(PhoneInsetsProviderElement(insets))) {
        CompositionLocalProvider(LocalPlatformWindowInsets provides insets, content = content)
    }
}

/** Equal while it carries the same insets object (PhoneWindowInsets keeps identity equality). */
private data class PhoneInsetsProviderElement(
    private val insets: PlatformWindowInsets,
) : ModifierNodeElement<PhoneInsetsProviderNode>() {
    override fun create(): PhoneInsetsProviderNode = PhoneInsetsProviderNode(insets)

    override fun update(node: PhoneInsetsProviderNode) = node.update(insets)
}

private class PhoneInsetsProviderNode(private var insets: PlatformWindowInsets) : PlatformWindowInsetsProviderNode() {
    override fun calculatePlatformInsets(ancestorWindowInsets: PlatformWindowInsets): PlatformWindowInsets = insets

    fun update(next: PlatformWindowInsets) {
        if (next === insets) return
        insets = next
        windowInsetsInvalidated()
    }
}
