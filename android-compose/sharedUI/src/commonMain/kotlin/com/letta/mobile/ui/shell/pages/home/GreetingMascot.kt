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
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.greeting.GreetingGesture
import com.letta.mobile.data.home.greeting.GreetingInput
import com.letta.mobile.data.home.greeting.GreetingIntent
import com.letta.mobile.data.home.greeting.GreetingTarget
import com.letta.mobile.data.home.greeting.intentFor
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
    val self = GreetingTarget.Actor(agent.id)
    val intent by remember(scene, agent.id) { derivedStateOf { scene.state.intentFor(agent.id) } }
    val identity = LocalMascotRegistry.current.identities[agent.id]
    val host = LocalMascotHost.current
    val live = !LocalReducedMotion.current && host.available && identity != null
    val director = if (live && identity != null) remember(host, agent.id, identity) { host.entry(agent.id, identity)?.director } else null
    val wake = remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    if (director != null) {
        val drive = remember(director, scene, density) {
            GreetingDrive(director, scene, self, with(density) { LettaSurfaceSizes.mascotGazeMinReach.toPx() })
        }
        DriveDirector(drive, intent) { wake.intValue++ }
    }
    val onHover: (Boolean) -> Unit = remember(scene, self) {
        { hovered ->
            when {
                hovered -> scene.send(GreetingInput.PointerOver(self))
                scene.state.pointer == self -> scene.send(GreetingInput.PointerOver(null))
            }
        }
    }
    val nudge by animateDpAsState(nudgeOf(intent, scene, self), label = "greeting-nudge")
    val onTap = { scene.send(GreetingInput.Tapped(self)) }
    Box(
        modifier
            .offset(x = nudge)
            .onGloballyPositioned { scene.place(self, it.boundsInWindow()) }
            .testTag(HomeGreetingTags.mascot(agent.id)),
    ) {
        CompositionLocalProvider(LocalMascotWake provides wake.intValue, LocalMascotHover provides onHover) {
            if (live && identity != null) {
                MascotLive(agent.id, identity, size, onClick = onTap)
            } else {
                AgentAvatar(agent.id, agent.name, size, live = false, onClick = onTap)
            }
        }
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

/** Plays [intent] on the director, waking the scene ([onWake]) for each beat so it is seen. */
@Composable
private fun DriveDirector(drive: GreetingDrive, intent: GreetingIntent, onWake: () -> Unit) {
    val director = drive.director
    LaunchedEffect(drive, intent.look) {
        val look = intent.look ?: return@LaunchedEffect
        onWake()
        while (true) {
            drive.gazeAt(look)?.let(director::leadGaze)
            delay(LOOK_REFRESH)
        }
    }
    val perked = remember(drive) { mutableIntStateOf(0) }
    LaunchedEffect(drive, intent.perk) {
        val now = if (intent.perk) 1 else 0
        if (now == perked.intValue) return@LaunchedEffect
        perked.intValue = now
        director.onEvent(MascotEvent.Hover(intent.perk))
        onWake()
    }
    val played = remember(drive) { mutableIntStateOf(intent.gestureSerial) }
    LaunchedEffect(drive, intent.gestureSerial) {
        val gesture = intent.gesture
        if (gesture != null && intent.gestureSerial > played.intValue) {
            director.onEvent(MascotEvent.DoubleTap(trickFor(gesture)))
            onWake()
        }
        played.intValue = intent.gestureSerial
    }
    LaunchedEffect(drive, intent.lean) {
        val lean = intent.lean
        director.setMotion(if (lean == null) 0f else sideOf(drive.scene, drive.self, lean) * LEAN_MOTION, 0f)
        if (lean != null) onWake()
    }
    val smiling = remember(drive) { mutableIntStateOf(0) }
    LaunchedEffect(drive, intent.happy) {
        val now = if (intent.happy) 1 else 0
        if (now == smiling.intValue) return@LaunchedEffect
        smiling.intValue = now
        director.onEvent(MascotEvent.Petting(intent.happy))
        onWake()
    }
    LaunchedEffect(drive, intent.hop) {
        if (intent.hop == null) return@LaunchedEffect
        try {
            awaitCancellation()
        } finally {
            director.onEvent(MascotEvent.Landed(HOP_IMPACT))
        }
    }
    DisposableEffect(drive) {
        onDispose {
            director.setMotion(0f, 0f)
            if (smiling.intValue == 1) director.onEvent(MascotEvent.Petting(false))
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
