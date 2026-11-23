package com.letta.mobile.ui.icons

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * The app icon for a desktop window's title bar and taskbar entry: the brand source's PNG
 * (`brand.icon.png` in branding/brand.properties), which the desktop build stages onto the classpath.
 * Null when the resource is missing, so a window still opens.
 */
object BakenAppIcon {
    fun painter(): Painter? =
        BakenAppIcon::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
        }

    private const val RESOURCE = "/icons/baken-app-icon.png"
}
