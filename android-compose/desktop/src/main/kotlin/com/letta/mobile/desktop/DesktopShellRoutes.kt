package com.letta.mobile.desktop

import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.data.shell.settingsSection
import com.letta.mobile.data.shell.skillsSection

/**
 * letta-mobile-ckmub.3.4: the desktop's route model - the selected [DesktopDestination] (what the
 * rail, palette and saved state know) plus the selection that refines it - and its mapping to and
 * from the shared [ShellDestination]. The desktop shell only maps; the page is the shared ShellPage.
 *
 * The agent editor and a canvas are panels beside the conversation on the desktop, not pages:
 * [editAgentId] and [canvasOpen] ride on the Conversations route.
 */
internal data class DesktopShellRoute(
    val destination: DesktopDestination,
    val agentId: String? = null,
    val conversationId: String? = null,
    val editAgentId: String? = null,
    val canvasOpen: Boolean = false,
    val canvasId: String? = null,
    val skillsSection: ShellSkillsSection = ShellSkillsSection.Skills,
    val settingsSection: ShellSettingsSection? = null,
) {
    /** The page the main pane shows: the route without the panels beside it. */
    val page: DesktopShellRoute get() = copy(editAgentId = null, canvasOpen = false, canvasId = null)
}

private val DesktopDestinationByKind: Map<ShellPageKind, DesktopDestination> = mapOf(
    ShellPageKind.Home to DesktopDestination.Home,
    ShellPageKind.Chat to DesktopDestination.Conversations,
    ShellPageKind.Canvas to DesktopDestination.Conversations,
    ShellPageKind.AgentEditor to DesktopDestination.Conversations,
    ShellPageKind.Memory to DesktopDestination.Memory,
    ShellPageKind.Schedules to DesktopDestination.Schedules,
    ShellPageKind.Channels to DesktopDestination.Channels,
    ShellPageKind.Skills to DesktopDestination.Agents,
    ShellPageKind.Settings to DesktopDestination.Settings,
    ShellPageKind.Providers to DesktopDestination.Providers,
    ShellPageKind.Overview to DesktopDestination.Overview,
)

/** The desktop route that shows [this]. */
internal fun ShellDestination.toDesktopRoute(): DesktopShellRoute {
    val canvas = this as? ShellDestination.Canvas
    return DesktopShellRoute(
        destination = DesktopDestinationByKind.getValue(kind),
        agentId = agentId,
        conversationId = conversationId,
        editAgentId = (this as? ShellDestination.AgentEditor)?.agentId,
        canvasOpen = canvas != null,
        canvasId = canvas?.canvasId,
        skillsSection = skillsSection,
        settingsSection = settingsSection,
    )
}

/** Builds the shared destination of each desktop destination from the route's selection. */
private val ShellDestinationOfDesktop: Map<DesktopDestination, (DesktopShellRoute) -> ShellDestination> = mapOf(
    DesktopDestination.Home to { _ -> ShellDestination.Home },
    DesktopDestination.Overview to { _ -> ShellDestination.Overview },
    DesktopDestination.Agents to { r -> ShellDestination.Skills(r.agentId, r.skillsSection) },
    DesktopDestination.Memory to { r -> ShellDestination.Memory(r.agentId) },
    DesktopDestination.Schedules to { r -> ShellDestination.Schedules(r.agentId) },
    DesktopDestination.Channels to { _ -> ShellDestination.Channels },
    DesktopDestination.Providers to { _ -> ShellDestination.Providers },
    DesktopDestination.Conversations to ::conversationsDestination,
    DesktopDestination.Settings to { r -> ShellDestination.Settings(r.settingsSection) },
)

private fun conversationsDestination(route: DesktopShellRoute): ShellDestination {
    route.editAgentId?.let { return ShellDestination.AgentEditor(it) }
    if (route.canvasOpen) return ShellDestination.Canvas(route.canvasId, route.conversationId, route.agentId)
    return ShellDestination.Chat(route.agentId, route.conversationId)
}

/** The shared destination this desktop route shows. */
internal fun DesktopShellRoute.toShellDestination(): ShellDestination = ShellDestinationOfDesktop.getValue(destination)(this)
