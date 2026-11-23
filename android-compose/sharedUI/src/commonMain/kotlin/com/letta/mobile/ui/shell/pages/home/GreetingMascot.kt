package com.letta.mobile.ui.shell.pages.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotDirector
import com.letta.mobile.avatar.core.MascotEvent
import com.letta.mobile.avatar.core.gaze.GazeMath
import com.letta.mobile.avatar.core.gaze.GazePoint
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.greeting.GreetingGesture
import com.letta.mobile.data.home.greeting.GreetingInput
import com.letta.mobile.data.home.greeting.GreetingIntent
import com.letta.mobile.data.home.greeting.GreetingTarget
import com.letta.mobile.data.home.greeting.intentFor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.LocalMascotHover
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.LocalMascotWake
import com.letta.mobile.ui.mascot.MascotLive
import com.letta.mobile.ui.mascot.toGazeRect
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds

/**
 * One agent on the greeting's stage: its live mascot when the host can draw one (a still otherwise,
 * and always under reduced motion), reporting hover and taps to the [scene] and playing the intent
 * the reducer gives it through the agent's own director, at its lineup [size]. The agent's sustained
 * state stays its presence; this only adds looks, a perk, a lean, a hop, a happy face and a wave.
 * Hover is read on the body (the mascot's own hit area), never the whole tile: tiles overlap in the
 * lineup, and a neighbour's tile must not take the pointer.
 */
@Composable
internal fun GreetingMascot(agent: HomeAgentRef, scene: GreetingScene, size: Dp, modifier: Modifier = Modifier) {
    val actor = remember(agent, scene) { GreetingActorHandle(agent, scene) }
    val intent by remember(actor) { derivedStateOf { scene.state.intentFor(agent.id) } }
    val identity = liveIdentity(AgentId(agent.id))
    val wake = remember { mutableIntStateOf(0) }
    if (identity != null) DriveLive(actor, identity, intent) { wake.intValue++ }
    val nudge by animateDpAsState(nudgeOf(intent, scene, actor.self), label = "greeting-nudge")
    Box(
        modifier
            .offset(x = nudge)
            .onGloballyPositioned { scene.place(actor.self, it.boundsInWindow()) }
            .testTag(HomeGreetingTags.mascot(AgentId(agent.id))),
    ) {
        CompositionLocalProvider(LocalMascotWake provides wake.intValue, LocalMascotHover provides actor.onHover) {
            GreetingMascotFace(agent, identity, size, actor.onTap)
        }
    }
}

/** One agent as an actor of the greeting: its target, and what its taps and hovers tell the scene. */
private class GreetingActorHandle(val agent: HomeAgentRef, val scene: GreetingScene) {
    val self = GreetingTarget.Actor(agent.id)
    val onTap: () -> Unit = { scene.send(GreetingInput.Tapped(self)) }

    /** The pointer on the body is this actor; leaving it clears the pointer only if it was still here. */
    val onHover: (Boolean) -> Unit = { hovered ->
        val leftUs = !hovered && scene.state.pointer == self
        if (hovered) scene.send(GreetingInput.PointerOver(self))
        if (leftUs) scene.send(GreetingInput.PointerOver(null))
    }
}

/** [agent]'s identity when its mascot draws live here; null for a still (no renderer, reduced motion, no identity). */
@Composable
private fun liveIdentity(agent: AgentId): MascotIdentity? {
    val identity = LocalMascotRegistry.current.identities[agent.value]
    val drawsLive = !LocalReducedMotion.current && LocalMascotHost.current.available
    return identity.takeIf { drawsLive }
}

/** Drives the agent's own director with the intent, once the host has its live entry. */
@Composable
private fun DriveLive(actor: GreetingActorHandle, identity: MascotIdentity, intent: GreetingIntent, onWake: () -> Unit) {
    val host = LocalMascotHost.current
    val director = remember(host, actor, identity) { host.entry(actor.agent.id, identity)?.director } ?: return
    val density = LocalDensity.current
    val drive = remember(director, actor, density) {
        GreetingDrive(director, actor.scene, actor.self, with(density) { LettaSurfaceSizes.mascotGazeMinReach.toPx() })
    }
    DriveDirector(drive, intent, onWake)
}

/** The live character when there is an [identity] to draw, the agent's still avatar otherwise. */
@Composable
private fun GreetingMascotFace(agent: HomeAgentRef, identity: MascotIdentity?, size: Dp, onTap: () -> Unit) {
    if (identity != null) {
        MascotLive(agent.id, identity, size, onClick = onTap)
    } else {
        AgentAvatar(agent.id, agent.name, size, live = false, onClick = onTap)
    }
}

/** A hop or a lean moves the tile a little toward what it is about: a hop by [GreetingHop], a lean by half. */
private fun nudgeOf(intent: GreetingIntent, scene: GreetingScene, self: GreetingTarget): Dp {
    val toward = intent.hop ?: intent.lean ?: return 0.dp
    val side = sideOf(scene, self, toward)
    return if (intent.hop != null) GreetingHop * side else GreetingHop * side / 2f
}

/** -1 when [toward] is to the left of [self], 1 to the right, 0 when it is straight above or below. */
private fun sideOf(scene: GreetingScene, self: GreetingTarget, toward: GreetingTarget): Float {
    val me = scene.rects[self] ?: return 0f
    val them = scene.rects[toward] ?: return 0f
    val dx = them.center.x - me.center.x
    return if (abs(dx) < me.width / 2f) 0f else sign(dx)
}

/** What a mascot's director needs to aim at the other actors. */
private class GreetingDrive(val director: MascotDirector, val scene: GreetingScene, val self: GreetingTarget, val minReachPx: Float) {
    /** [target] in this mascot's gaze units; the user is straight ahead. */
    fun gazeAt(target: GreetingTarget): GazePoint? {
        val me = scene.rects[self] ?: return null
        // The user is straight ahead; so is the mark while it is not on stage.
        val them = scene.rects[target] ?: return GazePoint(0f, 0f)
        return GazeMath.pointerPxToGaze(GazePoint(them.center.x, them.center.y), me.toGazeRect(), minReachPx)
    }
}

/** Plays [intent] on the director, one channel at a time, waking the scene ([onWake]) for each beat so it is seen. */
@Composable
private fun DriveDirector(drive: GreetingDrive, intent: GreetingIntent, onWake: () -> Unit) {
    DriveLook(drive, intent.look, onWake)
    DriveToggle(drive, intent.perk, onWake) { MascotEvent.Hover(it) }
    DriveToggle(drive, intent.happy, onWake) { MascotEvent.Petting(it) }
    DriveGesture(drive, intent, onWake)
    DriveLean(drive, intent.lean, onWake)
    DriveHop(drive, intent.hop)
}

/** Keeps the eyes on [look] while it holds, re-aimed as the actors move. */
@Composable
private fun DriveLook(drive: GreetingDrive, look: GreetingTarget?, onWake: () -> Unit) {
    LaunchedEffect(drive, look) {
        if (look == null) return@LaunchedEffect
        onWake()
        while (true) {
            drive.gazeAt(look)?.let(drive.director::leadGaze)
            delay(LOOK_REFRESH)
        }
    }
}

/** A held state the rig shows while [on] (a perk, a happy face): one event each way, and off when the mascot leaves. */
@Composable
private fun DriveToggle(drive: GreetingDrive, on: Boolean, onWake: () -> Unit, event: (Boolean) -> MascotEvent) {
    val shown = remember(drive) { booleanArrayOf(false) }
    LaunchedEffect(drive, on) {
        if (on == shown[0]) return@LaunchedEffect
        shown[0] = on
        drive.director.onEvent(event(on))
        onWake()
    }
    DisposableEffect(drive) { onDispose { if (shown[0]) drive.director.onEvent(event(false)) } }
}

/** Plays the intent's one-shot each time its serial goes up (never the one already played when this composed). */
@Composable
private fun DriveGesture(drive: GreetingDrive, intent: GreetingIntent, onWake: () -> Unit) {
    val played = remember(drive) { intArrayOf(intent.gestureSerial) }
    LaunchedEffect(drive, intent.gestureSerial) {
        val gesture = intent.gesture
        if (gesture != null && intent.gestureSerial > played[0]) {
            drive.director.onEvent(MascotEvent.DoubleTap(trickFor(gesture)))
            onWake()
        }
        played[0] = intent.gestureSerial
    }
}

/** Leans the body toward [lean] while it holds; upright again when it ends or the mascot leaves. */
@Composable
private fun DriveLean(drive: GreetingDrive, lean: GreetingTarget?, onWake: () -> Unit) {
    LaunchedEffect(drive, lean) {
        val side = lean?.let { sideOf(drive.scene, drive.self, it) } ?: 0f
        drive.director.setMotion(side * LEAN_MOTION, 0f)
        if (lean != null) onWake()
    }
    DisposableEffect(drive) { onDispose { drive.director.setMotion(0f, 0f) } }
}

/** A hop lands when it ends: a soft landing for the rig. */
@Composable
private fun DriveHop(drive: GreetingDrive, hop: GreetingTarget?) {
    LaunchedEffect(drive, hop) {
        if (hop == null) return@LaunchedEffect
        try {
            awaitCancellation()
        } finally {
            drive.director.onEvent(MascotEvent.Landed(HOP_IMPACT))
        }
    }
}

/**
 * The rig has no wave yet, so a hello is the peekaboo trick and a cheer the jump-twirl; a real
 * wave reaction replaces the first when the rig gains one.
 */
private fun trickFor(gesture: GreetingGesture): Int = when (gesture) {
    GreetingGesture.WAVE -> PEEKABOO_TRICK
    GreetingGesture.CHEER -> TWIRL_TRICK
}

/** How far a cheering mascot hops toward its neighbour, and a concerned one leans (half of it). */
private val GreetingHop: Dp = LettaDimens.Space.md

private const val PEEKABOO_TRICK = 2
private const val TWIRL_TRICK = 1

/** How hard a concerned or huddled mascot leans (the rig's travel lean, -1..1). */
private const val LEAN_MOTION = 0.3f

/** How hard a hop lands (0..1): a soft one. */
private const val HOP_IMPACT = 0.3f

/** A look is re-aimed this often while it holds (the director's lead lasts a little longer). */
private val LOOK_REFRESH = 400.milliseconds
