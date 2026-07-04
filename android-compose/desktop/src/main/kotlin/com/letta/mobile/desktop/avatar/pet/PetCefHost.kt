package com.letta.mobile.desktop.avatar.pet

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import java.nio.ByteBuffer
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.ComposeOsrBrowser
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * CEF-side half of the pet window: boots the bundled Chromium (jcefmaven
 * downloads natives to `~/.letta-mobile/jcef-bundle` on first run), opens the
 * renderer page in an off-screen [ComposeOsrBrowser], and republishes its
 * BGRA frames as Compose [ImageBitmap]s with alpha intact — the pieces the
 * per-pixel transparent pet window composites onto the desktop.
 */
class PetCefHost private constructor(
    private val app: CefApp,
    private val client: CefClient,
    private val onLog: (String) -> Unit,
) {
    private val frameState = mutableStateOf<ImageBitmap?>(null)

    /** Latest browser frame; recomposes consumers as frames arrive (~30fps). */
    val frame: State<ImageBitmap?> = frameState

    @Volatile private var firstFrameLogged = false
    @Volatile private var alphaProbeLogged = false

    private var browser: ComposeOsrBrowser? = null

    /** Open [url] off-screen. One page per host; a second call replaces it. */
    fun open(url: String) {
        browser?.close(true)
        browser = ComposeOsrBrowser(client, url, true, ::publishFrame).also {
            it.createImmediately()
        }
    }

    /** Mirror the Compose window's size (dp), scale, and screen position. */
    fun updateViewport(width: Int, height: Int, scale: Double, screenX: Int, screenY: Int) {
        browser?.updateViewport(width, height, scale, screenX, screenY)
    }

    fun dispose() {
        browser?.close(true)
        browser = null
        client.dispose()
        app.dispose()
    }

    private fun publishFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (!firstFrameLogged) {
            firstFrameLogged = true
            onLog("first frame ${width}x$height") // CEF OSR is painting — key spike signal.
        }
        // CEF reuses the buffer after this callback returns — copy it out.
        // Image.makeRaster copies again into a Skia-owned allocation; fine for
        // the spike, frame pooling is a perf follow-up (PRD §7 lands in M2).
        val bytes = ByteArray(width * height * 4)
        buffer.rewind()
        buffer.get(bytes)
        // One-time transparency probe: the page corner must be alpha 0. If it
        // reads 255 the transparency loss is CEF/page-side; if 0 it is the
        // Compose window compositing — splits the search space from the log.
        if (!alphaProbeLogged && width > 64) {
            alphaProbeLogged = true
            onLog("frame alpha probe ${width}x$height corner=${bytes[3].toInt() and 0xFF} (0=transparent)")
        }
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        try {
            frameState.value = Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
        } catch (t: Throwable) {
            onLog("pet frame publish failed: ${t.message}")
        }
    }

    companion object {
        /**
         * Blocking: downloads the CEF bundle on first run (~200MB). Call off
         * the UI thread.
         */
        fun start(onLog: (String) -> Unit): PetCefHost {
            val builder = CefAppBuilder()
            builder.setInstallDir(
                defaultDesktopStateDirectory().resolve("jcef-bundle").toFile(),
            )
            builder.cefSettings.windowless_rendering_enabled = true
            builder.setProgressHandler(
                IProgressHandler { state: EnumProgress, percent: Float ->
                    if (state == EnumProgress.DOWNLOADING || state == EnumProgress.EXTRACTING) {
                        onLog("jcef $state ${percent.toInt().coerceAtLeast(0)}%")
                    }
                },
            )
            val app = builder.build()
            return PetCefHost(app, app.createClient(), onLog)
        }
    }
}
