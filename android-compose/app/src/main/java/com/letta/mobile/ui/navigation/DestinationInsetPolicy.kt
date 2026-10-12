package com.letta.mobile.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlin.reflect.KClass

/** Who pads a compact destination for the system bars in [AdaptiveScaffold]: the scaffold, or the page itself. */
internal enum class DestinationInsetPolicy {
    /** Legacy screens: padded and consumed by AdaptiveScaffold. */
    Host,

    /**
     * Screens that lay themselves out edge to edge, drawing behind the system bars and insetting
     * only their own controls: the chat, and the canvas (its board and the note opened full
     * screen). Padding them here boxed the board in between two bars of scaffold colour.
     */
    SelfVertical,

    /**
     * Shared pages hosted in LettaPageScaffold, which owns every window inset itself
     * (letta-mobile-ckmub.2.1): the insets pass through untouched, or they are padded twice.
     */
    PageScaffold,
    ;

    fun frame(innerPadding: PaddingValues, layoutDirection: LayoutDirection): Modifier = when (this) {
        PageScaffold -> Modifier
        SelfVertical -> Modifier.padding(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
        )
        Host -> Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
    }
}

/** Destinations whose host renders a shared page inside LettaPageScaffold. */
private val PageScaffoldRoutes: List<KClass<*>> = listOf(
    AdminRoute::class,
    ChannelsRoute::class,
    MemoryRoute::class,
    SkillsRoute::class,
    AllToolsRoute::class,
)

internal fun NavDestination?.insetPolicy(): DestinationInsetPolicy = when {
    this == null -> DestinationInsetPolicy.Host
    PageScaffoldRoutes.any { hasRoute(it) } -> DestinationInsetPolicy.PageScaffold
    TopLevelDestination.CHAT.isSelected(this) || hasRoute(CanvasRoute::class) -> DestinationInsetPolicy.SelfVertical
    else -> DestinationInsetPolicy.Host
}
