package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.ui.mascot.MascotStillStore
import com.letta.mobile.ui.mascot.MascotStills
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The one offscreen scene stills are captured in, on the Rive thread only. Created on the first
 * capture and kept: a new scene per capture was a new D3D11 device plus a first-render warm-up,
 * about 450 ms each, where a capture in a warm scene is a few milliseconds. Each capture writes the
 * next identity over the last and settles; the body's transition completes well inside the settle.
 */
private var captureScene: RiveDesktopScene? = null

/**
 * Renders [identity] offscreen as a still, as PNG: the identity written into the capture scene,
 * its state machine run [MascotStills.SETTLE_FRAMES] simulated frames so the view-model bindings
 * take effect, one frame read back, encoded off the Rive thread.
 */
internal suspend fun captureDesktopMascotStill(mascot: ByteArray, identity: MascotIdentity): ByteArray? {
    val size = MascotStills.SIZE_PX
    val pixels = withContext(RiveThread.dispatcher) {
        runCatching {
            val scene = captureScene ?: RiveDesktopScene.create().also { created ->
                runCatching { created.load(mascot) }.onFailure { created.close() }.getOrThrow()
                captureScene = created
            }
            RiveAvatarContract.applyIdentity(scene.inputSink, identity)
            repeat(MascotStills.SETTLE_FRAMES) { scene.advance(MascotStills.FRAME_SECONDS) }
            // A copy: the scene reuses its buffer for the next render.
            scene.render(size, size).copyOf()
        }.onFailure {
            // A broken scene is dropped, so the next capture starts clean.
            captureScene?.close()
            captureScene = null
        }.getOrNull()
    } ?: return null
    return withContext(Dispatchers.Default) { pngOfPremultipliedRgba(pixels, size, size) }
}

/**
 * PNG bytes of a premultiplied RGBA frame, top row first, as the bridge renders it. Encoded with
 * ImageIO rather than Skia: Skia's encoder signature differs between the Skiko builds Compose pulls
 * in, and a still must not depend on which one won the dependency resolution.
 */
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
