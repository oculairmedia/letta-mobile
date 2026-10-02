package com.letta.mobile.desktop.phone

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.desktop.parseSharedChatFlagValue

/*
 * Phone preview (docs/development/phone-preview.md): the real desktop app, or the fixture
 * playground, drawn as a phone - a phone-sized screen, the Touch idiom of the shared chat page, and
 * simulated status bar, gesture bar and keyboard. Dev-only; nothing here runs unless the launch asks
 * for it ([PHONE_ENV_VARIABLE] / [PHONE_SYSTEM_PROPERTY], or the playground's own entry point).
 */

/** Environment variable that launches the desktop app as a phone (`:desktop:runPhone` sets it). */
internal const val PHONE_ENV_VARIABLE = "LETTA_DESKTOP_PHONE"

/** System property alternative to [PHONE_ENV_VARIABLE]. */
internal const val PHONE_SYSTEM_PROPERTY = "letta.desktop.phone"

/** True when this launch asked for the phone preview (1/true/yes/on, either source). */
internal fun desktopPhoneModeRequested(
    systemProperty: (String) -> String? = System::getProperty,
    environmentVariable: (String) -> String? = System::getenv,
): Boolean =
    parseSharedChatFlagValue(systemProperty(PHONE_SYSTEM_PROPERTY)) ||
        parseSharedChatFlagValue(environmentVariable(PHONE_ENV_VARIABLE))

/**
 * A phone's screen in dp, with the system chrome the preview simulates. Values are Android's own
 * for a gesture-navigation Pixel: the status bar holds the camera cutout, the gesture bar is the
 * navigation inset, and [keyboardDp] is a typical Gboard height without its suggestion strip's
 * extras.
 */
@Immutable
internal data class PhoneDevice(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val statusBarDp: Int,
    val navigationBarDp: Int,
    /** The on-screen keyboard above the navigation bar, portrait. */
    val keyboardDp: Int,
    /** The keyboard in landscape, where it is shorter. */
    val landscapeKeyboardDp: Int,
    val cornerRadiusDp: Int,
) {
    companion object {
        val Pixel9Pro = PhoneDevice(
            name = "Pixel 9 Pro",
            widthDp = 412,
            heightDp = 915,
            statusBarDp = 40,
            navigationBarDp = 24,
            keyboardDp = 300,
            landscapeKeyboardDp = 200,
            cornerRadiusDp = 36,
        )
    }
}

/** The font scales Android's "Font size" setting steps through, in the order Ctrl+F cycles them. */
internal val PHONE_FONT_SCALES = listOf(1f, 1.15f, 1.3f, 1.5f, 2f, 0.85f)

/**
 * Android's "Display size": a larger setting raises the density, so fewer dp fit across the same
 * glass. Ctrl+Shift+D cycles Default, Large, Larger.
 */
internal val PHONE_DISPLAY_SIZES = listOf(1f, 1.1f, 1.2f)

/**
 * Everything the phone preview's shortcuts change, observed by the phone screen, the window and
 * the app. One per window.
 */
@Stable
internal class DesktopPhoneState(
    val device: PhoneDevice = PhoneDevice.Pixel9Pro,
    initialDark: Boolean = true,
    initialZoom: Float = 1f,
) {
    /**
     * How many window pixels (at the monitor's own scale) one phone dp takes. Below 1 when the
     * phone would not fit the monitor at 1:1. The live app keeps it for the window's life, so
     * resizing the window changes the phone's size in dp, as a resizable emulator does; the
     * playground fits it to each pane.
     */
    var zoom by mutableStateOf(initialZoom)
    var landscape by mutableStateOf(false)
    var fontScale by mutableStateOf(PHONE_FONT_SCALES.first())
    var displaySize by mutableStateOf(PHONE_DISPLAY_SIZES.first())
    var dark by mutableStateOf(initialDark)
    var reducedMotion by mutableStateOf(false)

    /** The simulated keyboard is up (it animates in and out; see [DesktopPhoneScreen]). */
    var keyboardVisible by mutableStateOf(false)

    /** Raise the keyboard whenever a text field starts an input session, as Android does. */
    var autoKeyboard by mutableStateOf(true)
    var deviceFrame by mutableStateOf(true)

    /** Left-button mouse input reaches Compose as touch (see [DesktopPhoneTouchPointer]). */
    var touchPointer by mutableStateOf(true)
    var showShortcuts by mutableStateOf(true)

    /** Counts Ctrl+Shift+S presses; the window saves a screenshot for each. */
    var screenshotRequests by mutableStateOf(0)

    /** The screen's current size in dp, reported by the phone screen as it lays out. */
    var screenWidthDp by mutableStateOf(device.widthDp)
    var screenHeightDp by mutableStateOf(device.heightDp)

    /** The last setting a shortcut changed, shown briefly over the screen; null when nothing is shown. */
    var toast by mutableStateOf<String?>(null)
    var toastSerial by mutableStateOf(0)

    val keyboardDp: Int get() = if (landscape) device.landscapeKeyboardDp else device.keyboardDp

    fun announce(message: String) {
        toast = message
        toastSerial += 1
    }

    /** One line for the window title: device, size, and the current presets. */
    fun titleSummary(prefix: String): String = buildString {
        append(prefix)
        append(" - ")
        append(device.name)
        append(' ')
        append(screenWidthDp)
        append('x')
        append(screenHeightDp)
        append(" dp")
        if (fontScale != 1f) append(" - font ").append(formatScale(fontScale))
        if (displaySize != 1f) append(" - display ").append(formatScale(displaySize))
        append(" - F1 shortcuts")
    }
}

internal fun formatScale(value: Float): String {
    val hundredths = Math.round(value * HUNDRED)
    val whole = hundredths / HUNDRED
    val fraction = hundredths % HUNDRED
    return when {
        fraction == 0 -> "$whole.0"
        fraction % TEN == 0 -> "$whole.${fraction / TEN}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

private const val HUNDRED = 100
private const val TEN = 10

/**
 * The phone shell's chrome the app binds into when it runs as a phone; null in the normal desktop
 * app, which then draws exactly as before.
 */
@Stable
internal class DesktopPhoneChrome(val state: DesktopPhoneState) {
    /** The agents and conversations panel (desktop's rail and sidebar), opened over the screen. */
    var drawerOpen by mutableStateOf(false)
}

/** Non-null only under the phone preview (`:desktop:runPhone`). */
internal val LocalDesktopPhone = staticCompositionLocalOf<DesktopPhoneChrome?> { null }
