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

/**
 * Destinations whose host renders a shared page inside LettaPageScaffold, by serial name. The names
 * come from the compile-time serializers: `hasRoute(KClass)` looks each serializer up reflectively,
 * which costs on the startup path in a minified build.
 */
private val PageScaffoldRouteNames: Set<String> by lazy {
    setOf(
        AdminRoute.serializer().descriptor.serialName,
        ChannelsRoute.serializer().descriptor.serialName,
        MemoryRoute.serializer().descriptor.serialName,
        SchedulesRoute.serializer().descriptor.serialName,
        SkillsRoute.serializer().descriptor.serialName,
        AllToolsRoute.serializer().descriptor.serialName,
    )
}

/** A type-safe destination's route is its serial name, then any `/{arg}` path or `?arg=` query. */
private fun NavDestination.routeName(): String? = route?.substringBefore('/')?.substringBefore('?')

internal fun NavDestination?.insetPolicy(): DestinationInsetPolicy = when {
    this == null -> DestinationInsetPolicy.Host
    TopLevelDestination.CHAT.isSelected(this) || hasRoute(CanvasRoute::class) -> DestinationInsetPolicy.SelfVertical
    routeName() in PageScaffoldRouteNames -> DestinationInsetPolicy.PageScaffold
    else -> DestinationInsetPolicy.Host
}
