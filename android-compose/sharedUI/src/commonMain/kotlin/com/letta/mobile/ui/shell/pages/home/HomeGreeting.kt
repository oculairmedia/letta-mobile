package com.letta.mobile.ui.shell.pages.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.greeting.GreetingCastMember
import com.letta.mobile.data.home.greeting.GreetingInput
import com.letta.mobile.data.home.greeting.GreetingRunTally
import com.letta.mobile.data.home.greeting.GreetingStatusAgent
import com.letta.mobile.data.home.greeting.GreetingTarget
import com.letta.mobile.data.home.greeting.greetingLine
import com.letta.mobile.data.home.greeting.greetingStatusLine
import com.letta.mobile.data.home.greeting.markIntent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.components.foundation.lettaClickable
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.greetingMood
import com.letta.mobile.ui.mascot.greetingRunTally
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Home's hello: two lines ("Good evening", then what the agents are up to) over a tight, organic
 * lineup of the user's agents, standing nearly shoulder to shoulder at their own sizes, heights and
 * tilts ([greetingLineup]). Nothing is scripted: presence, pointer, taps, typing and the connection go
 * into the greeting's reducer ([com.letta.mobile.data.home.greeting.GreetingPlay]) and each actor plays
 * the intent it gives back. The Baken mark joins the row only once the host can draw the real one.
 */
@Composable
internal fun HomeGreeting(cast: GreetingCast, options: HomeGreetingOptions, hydrating: Boolean, modifier: Modifier = Modifier) {
    val scene = rememberGreetingScene()
    GreetingInputs(scene, options, hydrating)
    GreetingClock(scene)
    Column(
        modifier = modifier.fillMaxWidth().testTag(HomeGreetingTags.GREETING),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        GreetingText(cast, options)
        GreetingStage(scene, cast.agents)
    }
}

/** Feeds the reducer the app's state that is not per agent: the link, loading, motion, typing, the arrival. */
@Composable
private fun GreetingInputs(scene: GreetingScene, options: HomeGreetingOptions, hydrating: Boolean) {
    val registry = LocalMascotRegistry.current
    val reduced = LocalReducedMotion.current
    LaunchedEffect(scene, reduced) { scene.send(GreetingInput.ReducedMotion(reduced)) }
    LaunchedEffect(scene, options.connection) { scene.send(GreetingInput.Connection(options.connection)) }
    LaunchedEffect(scene, hydrating) { scene.send(GreetingInput.Hydrating(hydrating)) }
    LaunchedEffect(scene) {
        scene.send(GreetingInput.Arrived(returning = GreetingLaunch.booted))
        GreetingLaunch.booted = true
    }
    TypingWatch(scene, registry)
}

/** A run of [agent]'s that ends while Home shows is a moment its neighbours react to. */
@Composable
private fun RunEndings(scene: GreetingScene, registry: MascotIdentityRegistry, agent: AgentId) {
    val tally = registry.greetingRunTally(agent)
    val seen = remember(agent) { arrayOfNulls<GreetingRunTally>(1) }
    LaunchedEffect(scene, tally) {
        val was = seen[0]
        seen[0] = tally
        if (was == null) return@LaunchedEffect
        if (tally.ok > was.ok) scene.send(GreetingInput.RunFinished(agent.value, ok = true))
        if (tally.notOk > was.notOk) scene.send(GreetingInput.RunFinished(agent.value, ok = false))
    }
}

/**
 * The composer's keystroke count is a plain counter (typing must not recompose anything), so it is
 * sampled a few times a second; a pause of [TYPING_QUIET] ends the typing.
 */
@Composable
private fun TypingWatch(scene: GreetingScene, registry: MascotIdentityRegistry) {
    LaunchedEffect(scene, registry) {
        var seen = registry.composerKeystrokes
        var quiet = 0
        while (true) {
            delay(TYPING_POLL)
            val now = registry.composerKeystrokes
            if (now != seen) {
                seen = now
                quiet = 0
                scene.send(GreetingInput.Typing(true))
            } else if (scene.state.typing && ++quiet * TYPING_POLL.inWholeMilliseconds >= TYPING_QUIET.inWholeMilliseconds) {
                scene.send(GreetingInput.Typing(false))
            }
        }
    }
}

/** "Good evening", and under it what the agents are up to. */
@Composable
private fun GreetingText(cast: GreetingCast, options: HomeGreetingOptions) {
    val registry = LocalMascotRegistry.current
    val hour = options.hour ?: remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val status = greetingStatusLine(
        cast.agents.map { GreetingStatusAgent(it.name, registry.greetingMood(AgentId(it.id)), cast.updatesToday[it.id] ?: 0) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        // The line opens the page, so it is the page's heading (Home draws no title above it).
        Text(
            greetingLine(hour),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag(HomeGreetingTags.LINE),
        )
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(HomeGreetingTags.STATUS),
        )
    }
}

/**
 * The lineup: as many agents as fit the stage's width, the mark among them once it can draw. Only the
 * agents that fit are the reducer's cast, so its neighbours are the ones the user sees side by side.
 */
@Composable
private fun GreetingStage(scene: GreetingScene, agents: List<HomeAgentRef>) {
    if (agents.isEmpty()) return
    val registry = LocalMascotRegistry.current
    val markHost = LocalBrandMarkHost.current
    val reduced = LocalReducedMotion.current
    val huddled by remember(scene) { derivedStateOf { scene.state.connection.unsettled && !scene.state.reducedMotion } }
    val members = lineupMembers(agents, registry, markHost.available)
    BoxWithConstraints(Modifier.fillMaxWidth().height(GreetingStageHeight).testTag(HomeGreetingTags.STAGE)) {
        val spots = greetingLineup(members, maxWidth, huddled)
        val onStage = spots.filterNot { it.mark }.map { GreetingCastMember(it.id, registry.greetingMood(AgentId(it.id))) }
        LaunchedEffect(scene, onStage) { scene.send(GreetingInput.Cast(onStage)) }
        val spec: AnimationSpec<Dp> = if (reduced) snap() else spring()
        spots.forEach { spot ->
            key(spot.id) {
                val x by animateDpAsState(spot.x, spec, label = "lineup-x")
                val place = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x, y = spot.lift - ShellDimens.homeLineupLift)
                    .zIndex(if (spot.front) 1f else 0f)
                    .rotate(spot.tilt)
                if (spot.mark) {
                    GreetingMark(scene, spot.size, place)
                } else {
                    val agent = agents.first { it.id == spot.id }
                    RunEndings(scene, registry, AgentId(agent.id))
                    GreetingMascot(agent, scene, spot.size, place)
                }
            }
        }
    }
}

private fun lineupMembers(agents: List<HomeAgentRef>, registry: MascotIdentityRegistry, withMark: Boolean): List<LineupMember> {
    val crew = agents.map { LineupMember(it.id, busy = registry.greetingMood(AgentId(it.id)).busy) }
    if (!withMark) return crew
    // The mark stands among the crew, a couple of places in, never at the end where it would look apart.
    return crew.toMutableList().apply { add(minOf(MARK_PLACE, size), LineupMember(MARK_ID, mark = true)) }
}

/** The Baken mark as the lineup's smallest member: an actor the cast looks at, answering hover and taps. */
@Composable
private fun GreetingMark(scene: GreetingScene, size: Dp, modifier: Modifier) {
    val mark by remember(scene) { derivedStateOf { scene.state.markIntent } }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(scene, hovered) {
        when {
            hovered -> scene.send(GreetingInput.PointerOver(GreetingTarget.Mark))
            scene.state.pointer == GreetingTarget.Mark -> scene.send(GreetingInput.PointerOver(null))
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        LocalBrandMarkHost.current.Mark(
            mark,
            Modifier
                .size(size * ShellDimens.homeLineupBodyShare)
                .hoverable(hover)
                .lettaClickable(role = Role.Button, shape = MaterialTheme.shapes.small) {
                    scene.send(GreetingInput.Tapped(GreetingTarget.Mark))
                }
                .semantics { contentDescription = MARK_LABEL }
                .onGloballyPositioned { scene.place(GreetingTarget.Mark, it.boundsInWindow()) }
                .testTag(HomeGreetingTags.MARK),
        )
    }
}

private const val MARK_ID = "baken-mark"
private const val MARK_PLACE = 2
private const val MARK_LABEL = "Baken"
private val TYPING_POLL = 250.milliseconds
private val TYPING_QUIET = 1_500.milliseconds
