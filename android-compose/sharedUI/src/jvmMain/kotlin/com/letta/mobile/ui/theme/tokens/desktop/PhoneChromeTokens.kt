package com.letta.mobile.ui.theme.tokens.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The desktop phone preview's simulated device: the bezel round the glass and the Android system
 * chrome drawn over the app (status bar, gesture bar, keyboard, toast, shortcut list). They copy a
 * real phone's metrics, not the app's design system, so the app's scale never moves with them.
 */
object PhoneChromeTokens {
    /** The bezel's width between the window edge and the glass. */
    val frameWidth: Dp = 10.dp

    /** The rim drawn round the glass. */
    val frameRimColor: Color = Color(0xFF3A3D42)

    /** The bezel behind a dark phone. */
    val bezelDark: Color = Color(0xFF0B0C0E)

    /** The bezel behind a light phone. */
    val bezelLight: Color = Color(0xFF1B1D21)

    /** The glass's corners: the device profile's [cornerRadius], already scaled by the preview zoom. */
    fun glassShape(cornerRadius: Dp): Shape = RoundedCornerShape(cornerRadius)

    /** The status bar clock: Android's 14sp medium. */
    val statusText: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)

    /** Inset of the clock and the status icons from the screen's sides. */
    val statusSidePadding: Dp = 24.dp

    /** Gap between the status icons. */
    val statusIconGap: Dp = 6.dp

    /** The front camera's punch hole. */
    val cameraSize: Dp = 12.dp

    /** The battery glyph's body width. */
    val batteryWidth: Dp = 20.dp

    /** The battery glyph's body height. */
    val batteryHeight: Dp = 10.dp

    /** The battery glyph's body corners. */
    val batteryShape: Shape = RoundedCornerShape(2.dp)

    /** The battery nub's width. */
    val batteryNubWidth: Dp = 2.dp

    /** The battery nub's height. */
    val batteryNubHeight: Dp = 4.dp

    /** Gap between the signal glyph's bars. */
    val signalGap: Dp = 1.5.dp

    /** Width of each signal bar. */
    val signalBarWidth: Dp = 3.dp

    /** The four signal bars' heights, shortest first. */
    val signalBarHeights: List<Dp> = listOf(4.dp, 6.dp, 8.dp, 10.dp)

    /** The gesture handle's width. */
    val gestureHandleWidth: Dp = 108.dp

    /** The gesture handle's height. */
    val gestureHandleHeight: Dp = 4.dp

    /** The keyboard's suggestion strip height. */
    val suggestionStripHeight: Dp = 44.dp

    /** A word in the suggestion strip, and the strip's hide glyph: 15sp. */
    val suggestionText: TextStyle = TextStyle(fontSize = 15.sp)

    /** Gap between key caps. */
    val keyGap: Dp = 4.dp

    /** A key cap's corners. */
    val keyShape: Shape = RoundedCornerShape(6.dp)

    /** A key cap's label: 18sp. */
    val keyText: TextStyle = TextStyle(fontSize = 18.sp)

    /** The toast's gap below the status bar. */
    val toastGap: Dp = 12.dp

    /** The toast's capsule corners. */
    val toastShape: Shape = RoundedCornerShape(20.dp)

    /** Padding inside the toast. */
    val toastPadding: Dp = 12.dp

    /** The shortcut list's tonal elevation. */
    val overlayElevation: Dp = 6.dp

    /** The shortcut list's margin from the screen's edges. */
    val overlayMargin: Dp = 16.dp

    /** Padding inside the shortcut list. */
    val overlayPadding: Dp = 16.dp

    /** Gap between shortcut rows. */
    val overlayRowGap: Dp = 4.dp

    /** The shortcut list's key column width. */
    val overlayKeyColumn: Dp = 104.dp

    /** The phone shell's header pills: tonal and shadow elevation. */
    val headerElevation: Dp = 3.dp

    /** A phone destination's app bar height. */
    val topBarHeight: Dp = 56.dp
}
