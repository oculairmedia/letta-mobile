package com.letta.mobile.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import kotlin.reflect.KClass

/**
 * letta-mobile-ckmub.3.4: Android's route model (the typed navigation routes in [Routes]) mapped to
 * and from the shared [ShellDestination]. The nav host only maps; the shared pages are drawn by
 * ShellPage inside each route's container screen.
 *
 * Two Android routes cannot carry everything a destination can, and say so here rather than invent
 * arguments: a chat needs its agent ([ShellDestination.Chat] without one opens the conversation
 * list), and the standalone Tools route has no agent ([ShellDestination.Skills] on Tools drops it).
 * Routes with no shared destination (the pairing scanner, telemetry, system access, debug) map to null.
 */
@Suppress("NoAnyType") // Navigation routes are typed objects of unrelated classes.
private val AndroidRouteOfKind: Map<ShellPageKind, (ShellDestination) -> Any> = mapOf(
    ShellPageKind.Home to { _ -> AdminRoute },
    ShellPageKind.Chat to { d -> d.agentId?.let { AgentChatRoute(agentId = it, conversationId = d.conversationId) } ?: ConversationsRoute },
    ShellPageKind.Canvas to { d ->
        val canvas = d as ShellDestination.Canvas
        CanvasRoute(canvasId = canvas.canvasId.orEmpty(), conversationId = canvas.conversationId, agentId = canvas.agentId)
    },
    ShellPageKind.Memory to { d -> MemoryRoute(d.agentId) },
    ShellPageKind.Schedules to { d -> SchedulesRoute(d.agentId) },
    ShellPageKind.Channels to { _ -> ChannelsRoute },
    ShellPageKind.Skills to { d ->
        if ((d as ShellDestination.Skills).section == ShellSkillsSection.Tools) AllToolsRoute else SkillsRoute(d.agentId)
    },
    ShellPageKind.Settings to { d ->
        if ((d as ShellDestination.Settings).section == ShellSettingsSection.Pairing) PairingInviteRoute else ConfigRoute()
    },
    ShellPageKind.AgentEditor to { d -> EditAgentRoute(d.agentId) },
    ShellPageKind.Providers to { _ -> ProvidersRoute },
    ShellPageKind.Overview to { _ -> AboutRoute },
    ShellPageKind.Runs to { _ -> RunsRoute },
    ShellPageKind.Templates to { _ -> TemplatesRoute },
    ShellPageKind.Projects to { _ -> ProjectsRoute },
    ShellPageKind.Plugins to { _ -> PluginsRoute },
)

/** The Android navigation route that shows [this]. */
@Suppress("NoAnyType")
fun ShellDestination.toAndroidRoute(): Any = AndroidRouteOfKind.getValue(kind)(this)

@Suppress("NoAnyType")
private val ShellDestinationOfRoute: Map<KClass<*>, (Any) -> ShellDestination> = mapOf(
    AdminRoute::class to { _ -> ShellDestination.Home },
    ConversationsRoute::class to { _ -> ShellDestination.Chat() },
    AgentChatRoute::class to { r -> (r as AgentChatRoute).let { ShellDestination.Chat(it.agentId, it.conversationId) } },
    CanvasRoute::class to { r ->
        (r as CanvasRoute).let { ShellDestination.Canvas(it.canvasId.ifEmpty { null }, it.conversationId, it.agentId) }
    },
    MemoryRoute::class to { r -> ShellDestination.Memory((r as MemoryRoute).agentId) },
    SchedulesRoute::class to { r -> ShellDestination.Schedules((r as SchedulesRoute).agentId) },
    ChannelsRoute::class to { _ -> ShellDestination.Channels },
    SkillsRoute::class to { r -> ShellDestination.Skills((r as SkillsRoute).agentId) },
    AllToolsRoute::class to { _ -> ShellDestination.Skills(section = ShellSkillsSection.Tools) },
    ConfigRoute::class to { _ -> ShellDestination.Settings() },
    PairingInviteRoute::class to { _ -> ShellDestination.Settings(ShellSettingsSection.Pairing) },
    EditAgentRoute::class to { r -> ShellDestination.AgentEditor((r as EditAgentRoute).agentId) },
    ProvidersRoute::class to { _ -> ShellDestination.Providers },
    AboutRoute::class to { _ -> ShellDestination.Overview },
    RunsRoute::class to { _ -> ShellDestination.Runs },
    TemplatesRoute::class to { _ -> ShellDestination.Templates },
    ProjectsRoute::class to { _ -> ShellDestination.Projects },
    PluginsRoute::class to { _ -> ShellDestination.Plugins },
    // The Home tab's root is the projects catalog too, without a back arrow.
    HomeRoute::class to { _ -> ShellDestination.Projects },
)

/** The shared destination an Android route shows, or null for a route outside the shared shell. */
@Suppress("NoAnyType")
fun shellDestinationOf(route: Any): ShellDestination? = ShellDestinationOfRoute[route::class]?.invoke(route)

/**
 * Navigates to [destination] through its Android route. (Not an overload of `navigate`: the
 * member `navigate(route: T)` would win and try to serialize the destination itself.)
 */
fun NavController.navigateTo(destination: ShellDestination, builder: NavOptionsBuilder.() -> Unit = {}) {
    navigate(destination.toAndroidRoute(), builder)
}
