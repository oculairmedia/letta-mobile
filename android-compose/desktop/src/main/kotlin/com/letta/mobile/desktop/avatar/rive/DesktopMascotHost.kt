package com.letta.mobile.desktop.avatar.rive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.ui.mascot.MascotEntries
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost

/** A live mascot on desktop: the native scene plus the shared runtime and director over it. */
class DesktopMascotEntry(val scene: RiveDesktopScene, runtime: RiveAvatarRuntime, identity: MascotIdentity) :
    MascotEntry(runtime, identity, applyState = { state: AvatarState -> runtime.applyState(state) }) {
    private val rive = runtime

    override suspend fun load() = rive.load(MASCOT_MODEL)

    override fun dispose() = scene.close()
}

/**
 * Desktop's contribution to the shared mascot: bring up a native scene per agent (the bridge
 * DLL + the shipped `.riv`) and paint it as a Compose node. Presence, time, gaze and sizing are
 * the shared [com.letta.mobile.ui.mascot.MascotAvatar]'s; nothing here is policy.
 */
object DesktopMascotHost : MascotHost {
    private val entries = MascotEntries<DesktopMascotEntry>(
        create = ::create,
        applyIdentity = { entry, identity -> RiveAvatarContract.applyIdentity(entry.scene.inputSink, identity) },
    )

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? =
        if (RiveBridgeNative.AVAILABLE) entries.get(agentId, identity) else null

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier) {
        RiveDesktopSurface((entry as DesktopMascotEntry).scene, modifier)
    }

    fun closeAll() = entries.closeAll()

    private fun create(identity: MascotIdentity): DesktopMascotEntry? {
        val bytes = mascotBytes() ?: return null
        val scene = runCatching {
            RiveDesktopScene.create().also {
                it.load(bytes)
                // Host rule: identity before the first advance, or the first frame is a black body.
                RiveAvatarContract.applyIdentity(it.inputSink, identity)
                // Desynchronise: every scene starts at the same instant, so without this the
                // mascots on one screen blink, wander and fidget in lockstep. A random head start
                // (0-20 s in small steps, so the state machines take their transitions) breaks it.
                repeat(kotlin.random.Random.nextInt(0, 60)) { _ -> it.advance(kotlin.random.Random.nextFloat() * 0.3f + 0.05f) }
            }
        }.getOrNull() ?: return null
        return DesktopMascotEntry(scene, RiveAvatarRuntime(scene.inputSink), identity)
    }

    /** The shipped mascot, packaged as a JVM resource by :avatar:renderer-rive (the same bytes Android bundles as res/raw). */
    private fun mascotBytes(): ByteArray? =
        RiveAvatarContract::class.java.getResourceAsStream("/mascot/mascot.riv")?.use { it.readBytes() }
}
