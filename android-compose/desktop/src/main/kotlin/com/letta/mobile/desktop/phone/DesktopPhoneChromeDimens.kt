package com.letta.mobile.desktop.phone

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Tokens of the desktop phone preview's simulated device: the bezel around the glass, and the
 * Android system chrome drawn over the app (status bar, gesture bar, keyboard, toast, shortcut
 * list). They copy the phone's own look, not the app's design system, so they live here rather
 * than in LettaDimens: the app's scale must not move when the stand-in OS chrome is tuned.
 */

// The device frame.

/** The bezel's width between the window edge and the glass. */
internal val FRAME_WIDTH: Dp = 10.dp

/** The rim drawn round the glass. */
internal val FRAME_RIM: Dp = 1.dp
internal val FRAME_RIM_COLOR = Color(0xFF3A3D42)

/** The bezel behind a dark and a light phone. */
internal val BEZEL_DARK = Color(0xFF0B0C0E)
internal val BEZEL_LIGHT = Color(0xFF1B1D21)

/** The glass's corners: the device profile's radius, already scaled by the preview zoom. */
internal fun phoneGlassShape(cornerRadius: Dp): Shape = RoundedCornerShape(cornerRadius)

// The status bar.

/** The clock in the status bar: Android's 14 sp medium. */
internal val PHONE_STATUS_TEXT = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
internal val STATUS_SIDE_PADDING: Dp = 24.dp
internal val STATUS_ICON_GAP: Dp = 6.dp

/** The front camera's punch hole. */
internal val CAMERA_SIZE: Dp = 12.dp

/** The battery glyph: a body with a nub. */
internal val BATTERY_WIDTH: Dp = 20.dp
internal val BATTERY_HEIGHT: Dp = 10.dp
internal val BATTERY_SHAPE: Shape = RoundedCornerShape(2.dp)
internal val BATTERY_NUB_WIDTH: Dp = 2.dp
internal val BATTERY_NUB_HEIGHT: Dp = 4.dp

/** The signal glyph: four rising bars. */
internal val SIGNAL_GAP: Dp = 1.5.dp
internal val SIGNAL_BAR_WIDTH: Dp = 3.dp
internal val SIGNAL_BAR_HEIGHTS: List<Dp> = listOf(4.dp, 6.dp, 8.dp, 10.dp)

// The gesture bar.

internal val GESTURE_HANDLE_WIDTH: Dp = 108.dp
internal val GESTURE_HANDLE_HEIGHT: Dp = 4.dp

// The keyboard (a Gboard stand-in).

internal val SUGGESTION_STRIP_HEIGHT: Dp = 44.dp

/** A word in the suggestion strip, and the strip's hide glyph. */
internal val PHONE_SUGGESTION_TEXT = TextStyle(fontSize = 15.sp)
internal val KEY_GAP: Dp = 4.dp
internal val KEY_SHAPE: Shape = RoundedCornerShape(6.dp)

/** A key cap's label. */
internal val PHONE_KEY_TEXT = TextStyle(fontSize = 18.sp)

// The toast and the shortcut list.

/** The toast's gap below the status bar. */
internal val TOAST_GAP: Dp = 12.dp
internal val TOAST_SHAPE: Shape = RoundedCornerShape(20.dp)
internal val TOAST_PADDING: Dp = 12.dp
internal val OVERLAY_ELEVATION: Dp = 6.dp
internal val OVERLAY_MARGIN: Dp = 16.dp
internal val OVERLAY_PADDING: Dp = 16.dp
internal val OVERLAY_ROW_GAP: Dp = 4.dp

/** The shortcut list's key column. */
internal val OVERLAY_KEY_COLUMN: Dp = 104.dp

// The app's stand-in bars (the phone shell's header pills and destination app bar).

/** The header pills' tonal and shadow elevation. */
internal val HEADER_ELEVATION: Dp = 3.dp

/** A destination's app bar: Material's 56 dp top app bar. */
internal val TOP_BAR_HEIGHT: Dp = 56.dp
