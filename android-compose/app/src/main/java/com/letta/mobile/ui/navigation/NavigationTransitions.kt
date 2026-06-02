package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween

internal const val DrillTransitionDurationMs = 320

internal val drillInEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(DrillTransitionDurationMs),
        initialOffset = { distance -> distance / 8 },
    ) + fadeIn(animationSpec = tween(DrillTransitionDurationMs))
}

internal val drillInExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(DrillTransitionDurationMs / 2))
}

internal val drillInPopEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(DrillTransitionDurationMs / 2))
}

internal val drillInPopExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(DrillTransitionDurationMs),
        targetOffset = { distance -> distance / 8 },
    ) + fadeOut(animationSpec = tween(DrillTransitionDurationMs))
}
