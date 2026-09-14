package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One live mascot scene per agent, kept for the life of the process. A scene that is closed
 * whenever its composable leaves composition restarts from its entry pose every time the user
 * switches view - the character keeps "closing". Holding it here means the same scene, with its
 * state, gaze and wander mid-flight, is drawn wherever that agent appears.
 *
 * Memory: a scene is a native artboard plus a D3D target sized on demand; a handful per session
 * is fine. The rollout's P3 adds the bitmap tier for lists, which does not use this.
 */
object DesktopMascotScenes {
    class Entry(val scene: RiveDesktopScene, val runtime: RiveAvatarRuntime, var identity: MascotIdentity)

    private val entries = HashMap<String, Entry>()
    private val scope = CoroutineScope(Dispatchers.Main)

    /** The scene for [key] (an agent id), created on first use; null when the bridge or the file is unavailable. */
    @Synchronized
    fun get(key: String, identity: MascotIdentity): Entry? {
        if (!RiveBridgeNative.AVAILABLE) return null
        entries[key]?.let { entry ->
            if (entry.identity != identity) {
                entry.identity = identity
                RiveAvatarContract.applyIdentity(entry.scene.inputSink, identity)
            }
            return entry
        }
        val bytes = mascotBytes() ?: return null
        val scene = runCatching {
            RiveDesktopScene.create().also {
                it.load(bytes)
                // Host rule: identity before the first advance, or the first frame is a black body.
                RiveAvatarContract.applyIdentity(it.inputSink, identity)
            }
        }.getOrNull() ?: return null
        val runtime = RiveAvatarRuntime(scene.inputSink)
        scope.launch { runtime.load(MASCOT_MODEL) }
        return Entry(scene, runtime, identity).also { entries[key] = it }
    }

    @Synchronized
    fun closeAll() {
        entries.values.forEach { it.scene.close() }
        entries.clear()
    }

    /** The shipped mascot, packaged as a JVM resource by :avatar:renderer-rive (the same bytes Android bundles as res/raw). */
    private fun mascotBytes(): ByteArray? =
        RiveAvatarContract::class.java.getResourceAsStream("/mascot/mascot.riv")?.use { it.readBytes() }
}
