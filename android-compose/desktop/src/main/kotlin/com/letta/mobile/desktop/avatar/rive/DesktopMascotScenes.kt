package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One live mascot per agent, kept for the life of the process: the native scene, the shared
 * [RiveAvatarRuntime], and the shared [AvatarDirector] that arbitrates its state. A scene that is
 * closed whenever its composable leaves composition restarts from its entry pose every time the
 * user switches view; holding it here means the same character, mid-thought, is drawn wherever
 * that agent appears.
 *
 * The director is the only thing that decides the mascot's state. Surfaces feed it presence
 * ([apply]) and time ([tickTo]); it drives the runtime through its state listener. That is the
 * same director Android will feed - nothing here is desktop policy.
 */
object DesktopMascotScenes {
    class Entry(val scene: RiveDesktopScene, val runtime: RiveAvatarRuntime, var identity: MascotIdentity) {
        val director = AvatarDirector(runtime).also { d ->
            d.addStateListener { _, enter -> runtime.applyState(enter) }
        }
        private var lastPresence = AgentPresence.IDLE
        private var lastTickNanos = 0L

        /** Feeds the director; a run that ends without an error is a completed task. */
        fun apply(presence: AgentPresence) {
            val was = lastPresence
            if (presence == was) return
            lastPresence = presence
            println("mascot presence ${identity.encode()}: $presence")
            director.setActivity(
                when (presence.activity) {
                    AgentActivityKind.THINKING -> AvatarActivity.THINKING
                    AgentActivityKind.SPEAKING -> AvatarActivity.SPEAKING
                    AgentActivityKind.IDLE -> AvatarActivity.IDLE
                },
            )
            director.setUserTyping(presence.userTyping)
            director.setAwaitingApproval(presence.awaitingApproval)
            if (presence.error && !was.error) director.notifyError()
            if (was.activity != AgentActivityKind.IDLE && presence.activity == AgentActivityKind.IDLE && !presence.error) {
                director.notifyTaskSucceeded()
            }
        }

        /** Advances the director's clocks once per frame, however many surfaces draw this agent. */
        fun tickTo(nowNanos: Long) {
            if (nowNanos == lastTickNanos) return
            val dt = if (lastTickNanos == 0L) 0f else ((nowNanos - lastTickNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastTickNanos = nowNanos
            if (dt > 0f) director.tick(dt)
        }
    }

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
