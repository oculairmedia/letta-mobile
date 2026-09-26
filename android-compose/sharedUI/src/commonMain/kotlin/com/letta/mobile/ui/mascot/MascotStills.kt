package com.letta.mobile.ui.mascot

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.ui.image.decodeImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a host keeps mascot stills between launches: PNG bytes by key. */
interface MascotStillStore {
    suspend fun read(key: String): ByteArray?

    suspend fun write(key: String, png: ByteArray)
}

/**
 * One still image per mascot identity, for every place a mascot is drawn but not moving.
 *
 * Drawing a still with the renderer paused was cheap but wrong: the identity (body, colour, turn)
 * reaches the character through view-model bindings that only take effect once the state machine
 * has run a few frames, so every paused mascot showed the file's default look. Running the state
 * machine for every idle tile would fix that at a cost paid per tile, per frame.
 *
 * Instead each identity is captured once: [capture] renders it offscreen - its own scene, the
 * identity applied, the state machine advanced until the bindings have settled, one frame read
 * back as PNG - and the image is kept in memory and in [store], keyed by the identity and the
 * mascot asset's [assetVersion] (a new mascot file makes new stills). After that a still is an
 * image: no scene, no state machine, no frame callbacks. A live mascot (an agent at work) is
 * unaffected and still runs the real thing.
 *
 * [images] is Compose state, so a tile waiting on its identity redraws the moment it lands.
 */
class MascotStills(
    private val assetVersion: String,
    private val store: MascotStillStore?,
    private val capture: suspend (MascotIdentity) -> ByteArray?,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeImageBitmap,
) {
    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val failed = mutableSetOf<String>()

    // One capture at a time: captures share the platform's one offscreen renderer, and a screen of
    // tiles asking at once must not capture the same identity twice - each waiter re-checks once it
    // has the lock and finds the image.
    private val captureLock = Mutex()

    /** [identity]'s still, or null until [ensure] has produced it. */
    fun get(identity: MascotIdentity): ImageBitmap? = images[keyOf(identity)]

    /**
     * Makes [identity]'s still available: from memory, else from [store], else by capturing it.
     * Reading a saved still never waits behind a capture - a list of agents seen before draws at
     * once - and only captures queue, one at a time, since they share the renderer. A new capture
     * is shown the moment it is decoded and saved after. An identity that cannot be captured is not
     * retried this session.
     */
    suspend fun ensure(identity: MascotIdentity) {
        val key = keyOf(identity)
        if (images.containsKey(key) || key in failed) return
        val saved = runCatching { store?.read(key) }.getOrNull()?.let(decode)
        if (saved != null) {
            images[key] = saved
            return
        }
        val png = captureLock.withLock {
            if (images.containsKey(key) || key in failed) return
            runCatching { capture(identity) }.getOrNull()
        }
        val image = png?.let(decode)
        if (png == null || image == null) {
            failed += key
            return
        }
        images[key] = image
        runCatching { store?.write(key, png) }
    }

    /** The key a still is kept under: format, asset, identity; safe as a file name. */
    fun keyOf(identity: MascotIdentity): String =
        "v$FORMAT-$assetVersion-${identity.encode().replace(':', '_')}"

    companion object {
        /** Bumped when how a still is framed or rendered changes, so old files are not reused. */
        const val FORMAT = 2

        /** The side, in pixels, a still is captured at; tiles draw it scaled down. */
        const val SIZE_PX = 256

        /**
         * Simulated frames the state machine runs before capture, so the identity bindings settle:
         * every body shape finishes its transition inside 30 (measured), with margin. Advancing does
         * not draw, so these cost next to nothing.
         */
        const val SETTLE_FRAMES = 40

        /** The simulated length of one settling frame, in seconds. */
        const val FRAME_SECONDS = 1f / 60f
    }
}
