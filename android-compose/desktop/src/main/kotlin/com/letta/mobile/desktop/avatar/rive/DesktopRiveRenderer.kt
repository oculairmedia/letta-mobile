package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotStillStore
import com.letta.mobile.ui.mascot.frame.FrameMascotHost
import com.letta.mobile.ui.mascot.frame.RiveFrameRenderer
import com.letta.mobile.ui.mascot.frame.RiveFrameScene
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Image

/**
 * Desktop's renderer for the shared frame pipeline: the native bridge DLL (rive-runtime on the
 * Rive Renderer's D3D11 backend) and the `.riv` :avatar:renderer-rive packages as a JVM resource.
 */
internal object DesktopRiveRenderer : RiveFrameRenderer {
    /** The shipped mascot (the same bytes Android bundles as res/raw). */
    private val mascotBytes: ByteArray? by lazy {
        RiveAvatarContract::class.java.getResourceAsStream("/mascot/mascot.riv")?.use { it.readBytes() }
    }

    override val available: Boolean get() = RiveBridgeNative.AVAILABLE && mascotBytes != null

    override val assetVersion: String by lazy { mascotAssetVersion(checkNotNull(mascotBytes)) }

    override val dispatcher: CoroutineDispatcher get() = RiveThread.dispatcher

    override val stillStore: MascotStillStore by lazy { DesktopMascotStillStore() }

    override fun createScene(): RiveFrameScene {
        val bytes = checkNotNull(mascotBytes) { "mascot.riv is not on the classpath" }
        val scene = RiveDesktopScene.create()
        // A scene that failed after creation is released, not leaked.
        runCatching { scene.load(bytes) }.onFailure { scene.close() }.getOrThrow()
        return scene
    }

    override fun encodePng(image: Image): ByteArray? = pngOfImage(image)
}

/** Every live mascot on desktop: the shared frame pipeline over the native bridge. */
val DesktopMascotHost: MascotHost = FrameMascotHost(DesktopRiveRenderer)
