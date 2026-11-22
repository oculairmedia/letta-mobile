package com.letta.mobile.ui.icons

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * The Baken app icon (letta-mobile-0sb6o): the brand kit's lit icon (svg/baken-app-icon-lit.svg) at 256 px, for a
 * desktop window's title bar and taskbar entry. Null when the resource is missing, so a window still opens.
 */
object BakenAppIcon {
    fun painter(): Painter? =
        BakenAppIcon::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
        }

    private const val RESOURCE = "/icons/baken-app-icon.png"
}
