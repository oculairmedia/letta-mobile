package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.ui.mascot.MascotStillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * PNG bytes of a rendered still, read back as premultiplied RGBA. Encoded with ImageIO rather than
 * Skia: Skia's encoder signature differs between the Skiko builds Compose pulls in, and a still must
 * not depend on which one won the dependency resolution.
 */
internal fun pngOfImage(image: Image): ByteArray? {
    val info = ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val pixels = Bitmap().use { bitmap ->
        if (!bitmap.allocPixels(info) || !image.readPixels(bitmap)) return null
        bitmap.readPixels() ?: return null
    }
    return pngOfPremultipliedRgba(pixels, image.width, image.height)
}

/** PNG bytes of a premultiplied RGBA frame, top row first, as the bridge renders it. */
internal fun pngOfPremultipliedRgba(rgba: ByteArray, width: Int, height: Int): ByteArray {
    val argb = IntArray(width * height) { i ->
        val o = i * 4
        (rgba[o + 3].toInt() and 0xFF shl 24) or
            (rgba[o].toInt() and 0xFF shl 16) or
            (rgba[o + 1].toInt() and 0xFF shl 8) or
            (rgba[o + 2].toInt() and 0xFF)
    }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
    image.setRGB(0, 0, width, height, argb, 0, width)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** Stills kept beside the canvases: `~/.letta/mascot-stills/<key>.png`, written atomically. */
internal class DesktopMascotStillStore(
    private val directory: Path = Paths.get(System.getProperty("user.home") ?: ".", ".letta", "mascot-stills"),
) : MascotStillStore {
    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = directory.resolve("$key.png")
        if (Files.exists(file)) Files.readAllBytes(file) else null
    }

    override suspend fun write(key: String, png: ByteArray) {
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val temp = Files.createTempFile(directory, "still_", ".tmp")
            try {
                Files.write(temp, png)
                Files.move(temp, directory.resolve("$key.png"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}

/** A short, stable fingerprint of the mascot asset: a new file means new stills. */
internal fun mascotAssetVersion(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
