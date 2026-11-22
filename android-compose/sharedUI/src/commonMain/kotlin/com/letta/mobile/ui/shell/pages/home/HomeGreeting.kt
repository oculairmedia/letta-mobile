package com.letta.mobile.ui.shell.pages.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.greeting.GreetingCastMember
import com.letta.mobile.data.home.greeting.GreetingInput
import com.letta.mobile.data.home.greeting.GreetingTarget
import com.letta.mobile.data.home.greeting.greetingLine
import com.letta.mobile.data.home.greeting.markIntent
import com.letta.mobile.data.home.greeting.GreetingRunTally
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
 * Home's hello: one short line with the Baken mark before it, and a few of the user's agents
 * standing under it, reacting to each other, the user and the app. Nothing here is a scripted
 * sequence: agent presence, pointer, taps, typing and the connection go into the greeting's reducer
 * ([com.letta.mobile.data.home.greeting.GreetingPlay]) and each actor plays the intent it gives back.
 */
@Composable
internal fun HomeGreeting(cast: List<HomeAgentRef>, options: HomeGreetingOptions, hydrating: Boolean, modifier: Modifier = Modifier) {
    val scene = rememberGreetingScene()
    GreetingInputs(scene, cast, options, hydrating)
    GreetingClock(scene)
    Column(
        modifier = modifier.fillMaxWidth().testTag(HomeGreetingTags.GREETING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        GreetingLine(scene, options)
        GreetingStage(scene, cast)
    }
}

/** Feeds the reducer what the app knows: the cast and its moods, run endings, typing, the link, loading, motion. */
@Composable
private fun GreetingInputs(scene: GreetingScene, cast: List<HomeAgentRef>, options: HomeGreetingOptions, hydrating: Boolean) {
    val registry = LocalMascotRegistry.current
    val reduced = LocalReducedMotion.current
    LaunchedEffect(scene, reduced) { scene.send(GreetingInput.ReducedMotion(reduced)) }
    val members = cast.map { GreetingCastMember(it.id, registry.greetingMood(it.id)) }
    LaunchedEffect(scene, members) { scene.send(GreetingInput.Cast(members)) }
    LaunchedEffect(scene, options.connection) { scene.send(GreetingInput.Connection(options.connection)) }
    LaunchedEffect(scene, hydrating) { scene.send(GreetingInput.Hydrating(hydrating)) }
    LaunchedEffect(scene) {
        scene.send(GreetingInput.Arrived(returning = GreetingLaunch.booted))
        GreetingLaunch.booted = true
    }
    cast.forEach { agent -> key(agent.id) { RunEndings(scene, registry, agent.id) } }
    TypingWatch(scene, registry)
}

/** A run of [agentId]'s that ends while Home shows is a moment its neighbours react to. */
@Composable
private fun RunEndings(scene: GreetingScene, registry: MascotIdentityRegistry, agentId: String) {
    val tally = registry.greetingRunTally(agentId)
    val seen = remember(agentId) { arrayOfNulls<GreetingRunTally>(1) }
    LaunchedEffect(scene, tally) {
        val was = seen[0]
        seen[0] = tally
        if (was == null) return@LaunchedEffect
        if (tally.ok > was.ok) scene.send(GreetingInput.RunFinished(agentId, ok = true))
        if (tally.notOk > was.notOk) scene.send(GreetingInput.RunFinished(agentId, ok = false))
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

/** The mark, then the line ("Good evening"). The mark is an actor too: the cast looks at it and it answers hover and taps. */
@Composable
private fun GreetingLine(scene: GreetingScene, options: HomeGreetingOptions) {
    val hour = options.hour ?: remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val mark by remember(scene) { derivedStateOf { scene.state.markIntent } }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(scene, hovered) {
        if (hovered) {
            scene.send(GreetingInput.PointerOver(GreetingTarget.Mark))
        } else if (scene.state.pointer == GreetingTarget.Mark) {
            scene.send(GreetingInput.PointerOver(null))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        LocalBrandMarkHost.current.Mark(
            mark,
            Modifier
                .size(ShellDimens.homeGreetingMark)
                .hoverable(hover)
                .lettaClickable(role = Role.Button, shape = MaterialTheme.shapes.small) {
                    scene.send(GreetingInput.Tapped(GreetingTarget.Mark))
                }
                .semantics { contentDescription = MARK_LABEL }
                .onGloballyPositioned { scene.place(GreetingTarget.Mark, it.boundsInWindow()) }
                .testTag(HomeGreetingTags.MARK),
        )
        Text(greetingLine(hour), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(HomeGreetingTags.LINE))
    }
}

/** The ground the cast stands on, centred under the line; the row draws in while the link is unsettled. */
@Composable
private fun GreetingStage(scene: GreetingScene, cast: List<HomeAgentRef>) {
    if (cast.isEmpty()) return
    val reduced = LocalReducedMotion.current
    val huddled by remember(scene) { derivedStateOf { scene.state.connection.unsettled && !scene.state.reducedMotion } }
    BoxWithConstraints(Modifier.fillMaxWidth().height(ShellDimens.homeGreetingMascot).testTag(HomeGreetingTags.STAGE)) {
        val slots = greetingSlots(cast.size, maxWidth, huddled)
        cast.forEachIndexed { index, agent ->
            key(agent.id) {
                val x by animateDpAsState(slots[index], if (reduced) snap() else spring(), label = "greeting-slot")
                GreetingMascot(agent, scene, Modifier.align(Alignment.Center).offset(x = x))
            }
        }
    }
}

private const val MARK_LABEL = "Baken"
private val TYPING_POLL = 250.milliseconds
private val TYPING_QUIET = 1_500.milliseconds
