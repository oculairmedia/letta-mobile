package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.projects.ProjectsPageState
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.templates.AgentTemplatesState
import com.letta.mobile.ui.agenteditor.AgentEditorPage
import com.letta.mobile.ui.agenteditor.AgentEditorPageOptions
import com.letta.mobile.ui.settings.SettingsPage
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.pages.channels.ChannelsPage
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageOptions
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPage
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageActions
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageState
import com.letta.mobile.ui.shell.pages.home.HomePage
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspacePage
import com.letta.mobile.ui.shell.pages.projects.ProjectsPage
import com.letta.mobile.ui.shell.pages.projects.ProjectsPageActions
import com.letta.mobile.ui.shell.pages.projects.ProjectsUnavailable
import com.letta.mobile.ui.shell.pages.runs.RunsPage
import com.letta.mobile.ui.shell.pages.runs.RunsPageActions
import com.letta.mobile.ui.shell.pages.runs.RunsPageState
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPage
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageOptions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.pages.skills.SkillsPage
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageOptions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageSection
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.shell.pages.templates.TemplatesPage
import com.letta.mobile.ui.shell.pages.templates.TemplatesPageActions

/** Draws the page for one kind of destination. */
fun interface ShellPageContent<in D : ShellDestination> {
    @Composable
    fun Content(destination: D, modifier: Modifier)
}

/**
 * letta-mobile-ckmub.3.4 (hard cut S5, primitive P8): what [ShellPage] draws each destination with.
 *
 * A host fills only the pages it can show right now (Android builds one per nav entry, the desktop
 * and web all of theirs); a destination without one goes to [fallback].
 *
 * - The shared pages ([home], [channels], [skills], [schedules], [memory], [settings], and the [admin] pages) are built from the shared
 *   page bindings below ([shellHomePage], ...), so the page itself is always the sharedUI one.
 * - [chat] is the platform's chat-page container: it binds the shared ChatSurface to the platform's
 *   session port (DesktopSharedChatPage, SharedChatPage, WebSharedChatPage). It stays a slot.
 * - [fallback] is TEMPORARY: the platform pages that have no shared page yet (listed in the bead
 *   letta-mobile-ckmub.3.4 and in the hosts' fallbacks). Each later slice moves one into sharedUI
 *   and deletes its fallback branch; copying a fallback page into another platform is a review blocker.
 */
class ShellPageDeps(
    home: ShellPageContent<ShellDestination.Home>? = null,
    chat: ShellPageContent<ShellDestination.Chat>? = null,
    canvas: ShellPageContent<ShellDestination.Canvas>? = null,
    memory: ShellPageContent<ShellDestination.Memory>? = null,
    schedules: ShellPageContent<ShellDestination.Schedules>? = null,
    channels: ShellPageContent<ShellDestination.Channels>? = null,
    skills: ShellPageContent<ShellDestination.Skills>? = null,
    settings: ShellPageContent<ShellDestination.Settings>? = null,
    agentEditor: ShellPageContent<ShellDestination.AgentEditor>? = null,
    providers: ShellPageContent<ShellDestination.Providers>? = null,
    admin: ShellAdminPages = ShellAdminPages(),
    val fallback: ShellPageContent<ShellDestination> = EmptyShellPage,
) {
    // Each page is registered under the kind of the destination type it was typed for above, so
    // the lookup in [pageFor] always hands a page the destination type it expects.
    private val pages: Map<ShellPageKind, ShellPageContent<*>> = listOf(
        ShellPageKind.Home to home,
        ShellPageKind.Chat to chat,
        ShellPageKind.Canvas to canvas,
        ShellPageKind.Memory to memory,
        ShellPageKind.Schedules to schedules,
        ShellPageKind.Channels to channels,
        ShellPageKind.Skills to skills,
        ShellPageKind.Settings to settings,
        ShellPageKind.AgentEditor to agentEditor,
        ShellPageKind.Providers to providers,
        ShellPageKind.Runs to admin.runs,
        ShellPageKind.Templates to admin.templates,
        ShellPageKind.Projects to admin.projects,
        ShellPageKind.Plugins to admin.plugins,
    ).mapNotNull { (kind, page) -> page?.let { kind to it } }.toMap()

    /**
     * letta-mobile-ckmub.3.12 (S10): Overview has no page of its own. It is the Settings page opened
     * on its diagnostics section, so a host with a Settings page never needs a fallback for it.
     */
    private val overview: ShellPageContent<ShellDestination>? = settings?.let { page ->
        ShellPageContent { _, modifier -> page.Content(ShellDestination.Settings(ShellSettingsSection.About), modifier) }
    }

    /** The kinds this host draws with a page of its own (not the fallback). */
    val registered: Set<ShellPageKind> get() = if (overview != null) pages.keys + ShellPageKind.Overview else pages.keys

    @Suppress("UNCHECKED_CAST")
    internal fun pageFor(destination: ShellDestination): ShellPageContent<ShellDestination> =
        pages[destination.kind] as ShellPageContent<ShellDestination>?
            ?: overview?.takeIf { destination.kind == ShellPageKind.Overview }
            ?: fallback
}

/**
 * letta-mobile-ckmub.2.6: the fleet's admin pages (the run monitor, the starter templates, the projects
 * catalog), built from [shellRunsPage], [shellTemplatesPage] and [shellProjectsPage].
 */
class ShellAdminPages(
    val runs: ShellPageContent<ShellDestination.Runs>? = null,
    val templates: ShellPageContent<ShellDestination.Templates>? = null,
    val projects: ShellPageContent<ShellDestination.Projects>? = null,
    /** The host's plugins (letta-mobile-cdgpr.9), built from [com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage]. */
    val plugins: ShellPageContent<ShellDestination.Plugins>? = null,
)

object ShellPageTags {
    const val EMPTY = "shell_page_empty"
}

/** A destination nobody can draw here: an empty page rather than a crash. */
val EmptyShellPage = ShellPageContent<ShellDestination> { _, modifier ->
    Box(modifier.fillMaxSize().testTag(ShellPageTags.EMPTY))
}

/**
 * The only page switch of the shell: [destination] -> its page. Platform navigation hosts map their
 * routes, deep links and back stack to a [ShellDestination] and call this; they never pick a page.
 */
@Composable
fun ShellPage(destination: ShellDestination, deps: ShellPageDeps, modifier: Modifier = Modifier) {
    // One state scope per kind of page: leaving Memory for Channels never hands Memory's saved view to Channels.
    key(destination.kind) { deps.pageFor(destination).Content(destination, modifier) }
}

// --- the shared pages ---------------------------------------------------------------------------

/** The shared Home page. */
fun shellHomePage(
    state: HomePageState,
    callbacks: HomePageCallbacks,
    options: HomePageOptions = HomePageOptions(),
) = ShellPageContent<ShellDestination.Home> { _, modifier ->
    HomePage(state = state, callbacks = callbacks, modifier = modifier, options = options)
}

/** The shared Channels page. */
fun shellChannelsPage(
    state: ChannelsPageState,
    actions: ChannelsPageActions,
    options: ChannelsPageOptions = ChannelsPageOptions(),
) = ShellPageContent<ShellDestination.Channels> { _, modifier ->
    ChannelsPage(state = state, actions = actions, modifier = modifier, options = options)
}

/** The shared Skills page, opened on the destination's section (the Tools anchor). */
fun shellSkillsPage(
    state: SkillsPageState,
    actions: SkillsPageActions,
    options: SkillsPageOptions = SkillsPageOptions(),
) = ShellPageContent<ShellDestination.Skills> { destination, modifier ->
    SkillsPage(
        state = state,
        actions = actions,
        modifier = modifier,
        options = options.copy(initialSection = destination.section.toPageSection()),
    )
}

/** The shared Schedules page (letta-mobile-ckmub.3.16). */
fun shellSchedulesPage(
    state: SchedulesPageState,
    actions: SchedulesPageActions,
    options: SchedulesPageOptions = SchedulesPageOptions(),
) = ShellPageContent<ShellDestination.Schedules> { _, modifier ->
    SchedulesPage(state = state, actions = actions, modifier = modifier, options = options)
}

/** The shared Memory page: the block graph and the agent's files. */
fun shellMemoryPage(binding: MemoryWorkspaceBinding) = ShellPageContent<ShellDestination.Memory> { _, modifier ->
    MemoryWorkspacePage(binding = binding, modifier = modifier)
}

/**
 * A chat destination without an agent: the fleet conversations page (letta-mobile-ckmub.2.4). A host
 * whose chat page is its own container registers this only where a chat route has no agent.
 */
fun shellConversationsPage(
    state: ConversationsPageState,
    actions: ConversationsPageActions,
) = ShellPageContent<ShellDestination.Chat> { _, modifier ->
    ConversationsPage(state = state, actions = actions, modifier = modifier)
}

/** The shared Settings page, opened on the destination's section (Pairing, About). */
fun shellSettingsPage(
    state: SettingsPageState,
    services: SettingsPageServices,
    onBack: (() -> Unit)? = null,
) = ShellPageContent<ShellDestination.Settings> { destination, modifier ->
    SettingsPage(state = state.copy(section = destination.section ?: state.section), services = services, modifier = modifier, onBack = onBack)
}

/**
 * letta-mobile-ckmub.3.24 (hard cut S15): what the shared agent editor is bound to. [controller] is the
 * host's editor for an agent id (null: a new agent), kept by the host for as long as it shows the page
 * (Android's view model, the desktop pane's composition); [options] wires the page for that editor.
 */
class AgentEditorBinding(
    val controller: @Composable (agentId: String?) -> AgentEditorController,
    val options: (agentId: String?, controller: AgentEditorController) -> AgentEditorPageOptions = { _, _ -> AgentEditorPageOptions() },
)

/** The shared agent editor, for the destination's agent or a new one. */
fun shellAgentEditorPage(binding: AgentEditorBinding) = ShellPageContent<ShellDestination.AgentEditor> { destination, modifier ->
    val controller = binding.controller(destination.agentId)
    val state by controller.state.collectAsState()
    AgentEditorPage(state = state, actions = controller, options = binding.options(destination.agentId, controller), modifier = modifier)
}

/** The shared run monitor (letta-mobile-ckmub.2.5); [slots] are the host's search field and refresh. */
fun shellRunsPage(
    state: RunsPageState,
    actions: RunsPageActions,
    slots: ChannelsPageSlots = ChannelsPageSlots(),
) = ShellPageContent<ShellDestination.Runs> { _, modifier ->
    RunsPage(state = state, actions = actions, modifier = modifier, slots = slots)
}

/** The shared starter templates (letta-mobile-ckmub.2.5). */
fun shellTemplatesPage(state: AgentTemplatesState, actions: TemplatesPageActions) =
    ShellPageContent<ShellDestination.Templates> { _, modifier ->
        TemplatesPage(state = state, actions = actions, modifier = modifier)
    }

/**
 * The shared projects catalog (letta-mobile-ckmub.2.5). Projects ride an Iroh host's relay: a host
 * whose backend cannot list them binds [shellProjectsUnavailablePage] instead.
 */
fun shellProjectsPage(
    state: ProjectsPageState,
    actions: ProjectsPageActions,
    slots: ChannelsPageSlots = ChannelsPageSlots(),
) = ShellPageContent<ShellDestination.Projects> { _, modifier ->
    ProjectsPage(state = state, actions = actions, modifier = modifier, slots = slots)
}

/** The Projects destination on a backend without projects: says so, and why (letta-mobile-ckmub.2.6). */
fun shellProjectsUnavailablePage(onOpenSettings: (() -> Unit)? = null) =
    ShellPageContent<ShellDestination.Projects> { _, modifier ->
        ProjectsUnavailable(onOpenSettings = onOpenSettings, modifier = modifier)
    }

fun ShellSkillsSection.toPageSection(): SkillsPageSection = when (this) {
    ShellSkillsSection.Skills -> SkillsPageSection.Skills
    ShellSkillsSection.Tools -> SkillsPageSection.Tools
}

fun SkillsPageSection.toShellSection(): ShellSkillsSection = when (this) {
    SkillsPageSection.Skills -> ShellSkillsSection.Skills
    SkillsPageSection.Tools -> ShellSkillsSection.Tools
}
