package com.letta.mobile.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasLibrary
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.ChatTextSize
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.canvas.toCanvasArchiveFilter
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopBranchOrigin
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopConversationMarks
import com.letta.mobile.desktop.chat.DesktopTranscriptExport
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsActions
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsState
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarkActions
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarks
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.sidebar.ShellSidebarInput
import com.letta.mobile.ui.shell.sidebar.ShellSidebarMapping
import com.letta.mobile.ui.shell.sidebar.shellSectionIcon
import kotlin.time.Clock


/**
 * letta-mobile-ckmub.3.10 (hard cut S7): the desktop's binding of the shared agent panel. The panel,
 * its rows and its dialogs are sharedUI ([com.letta.mobile.ui.shell.sidebar.ShellAgentPanel]); this
 * only maps the window's state to [ShellSidebarInput] and the panel's asks to the controllers.
 */
internal data class DesktopSidebarMarks(
    val deletingIds: Set<String> = emptySet(),
    val pinnedIds: Set<String> = emptySet(),
    /** letta-mobile-7mys7: each fork's source conversation (ConversationLineage), for "Forked from …". */
    val parentIds: Map<String, String> = emptyMap(),
    val settledIds: Set<String> = emptySet(),
    val markers: Map<String, String> = emptyMap(),
)

/** The window's sidebar inputs: what the shell frame does not already hold. */
internal data class DesktopSidebarInputs(
    val marks: DesktopSidebarMarks,
    val canvases: List<CanvasDocument>,
    val archivedCanvasIds: Set<CanvasId>,
    val activeCanvasId: CanvasId?,
    val chatOptions: ShellChatOptionsState?,
)

/** The shared panel's state for the window: the focused agent, its page, its stack's chats and every canvas. */
internal fun desktopSidebarPanelState(
    navigator: ShellWorkspaceState,
    frame: DesktopShellFrame,
    inputs: DesktopSidebarInputs,
): ShellAgentPanelState {
    val focus = frame.focus
    val destination = navigator.selectedDestination
    val mode = navigator.workPlayMode
    val archiveFilter = frame.lists.archiveFilter
    val input = desktopPanelPage(destination, mode).copy(
        agent = ShellPanelAgent(
            name = focus.selectedAgentName,
            orbIndex = focus.selectedAgentOrbIndex,
            agentId = focus.selectedAgentId,
            identity = focus.selectedAgentId?.let { focus.identityByAgentId[it] },
        ),
        archiveFilter = archiveFilter.toShellArchiveFilter(),
        conversations = frame.lists.agentConversations,
        archivedConversations = frame.lists.archivedAgentConversations,
        marks = ShellConversationMarks(
            selectedId = desktopOpenConversation(destination, frame.chatState.selectedConversationId),
            thinkingId = frame.activity.thinkingConversationId,
            deletingIds = inputs.marks.deletingIds,
            pinnedIds = inputs.marks.pinnedIds,
            parentIds = inputs.marks.parentIds,
            settledIds = inputs.marks.settledIds,
            markers = inputs.marks.markers,
        ),
        chatOptions = inputs.chatOptions,
        // The sidebar's Active / Archived / All applies to canvases as to chats.
        canvases = CanvasLibrary.filter(inputs.canvases, inputs.archivedCanvasIds, archiveFilter.toCanvasArchiveFilter()),
        activeCanvasId = inputs.activeCanvasId,
        archivedCanvasIds = inputs.archivedCanvasIds,
    )
    return ShellSidebarMapping.panelState(input, Clock.System.now())
}

/** Which page the panel marks for the open [destination]: Home, a section, or Settings. */
internal fun desktopPanelPage(destination: DesktopDestination, mode: WorkPlayMode): ShellSidebarInput = ShellSidebarInput(
    agent = ShellPanelAgent(name = ""),
    mode = mode,
    home = destination == DesktopDestination.Home,
    selectedSection = WorkPlayLens.navDestinations(mode).firstOrNull { lensNavTarget(mode, it).first == destination },
    settingsSelected = destination == DesktopDestination.Settings,
)

/** A conversation reads as open only while the conversation page shows. */
internal fun desktopOpenConversation(destination: DesktopDestination, selectedId: String?): String? =
    selectedId.takeIf { destination == DesktopDestination.Conversations }

/** The Chat options dialog's state: display options, the window's text size, auto-pin, and whether a chat is open. */
internal fun desktopChatOptionsState(
    display: ChatDisplayOptions,
    fontScale: Float,
    autoPin: Boolean,
    hasChat: Boolean,
): ShellChatOptionsState = ShellChatOptionsState(
    display = display,
    textSize = ChatTextSize.nearest(fontScale),
    autoPinNewChats = autoPin,
    canDownloadTranscript = hasChat,
)

/** What the shared panel's asks do on the desktop. */
internal fun desktopSidebarActions(
    context: DesktopShellContext,
    focus: DesktopAgentFocus,
    deleteBehavior: ConversationDeleteBehavior,
    setFontScale: (Float) -> Unit,
): ShellAgentPanelActions {
    val chatController = context.core.chatController
    val management = chatController.conversationManagement
    val navigator = context.navigator
    val canvasShell = context.core.canvasShell
    return ShellAgentPanelActions(
        onOpenSection = { navigator.navigate(lensNavTarget(navigator.workPlayMode, it).first) },
        onOpenSettings = { navigator.navigate(DesktopDestination.Settings) },
        onNewChat = { context.openNewChatForFocusedAgent(focus) },
        onEditAgent = { navigator.editAgentId = focus.selectedAgentId?.let(::AgentId) },
        onArchiveFilterChange = { chatController.setArchiveFilter(it.toConversationArchiveFilter()) },
        onConversationSelected = { context.router.openConversation(ConversationId(it)) },
        onArchiveConversation = chatController::setConversationArchived,
        onDeleteConversation = chatController::deleteConversation,
        deleteBehavior = deleteBehavior,
        onRenameConversation = { id, title ->
            management.rename(ConversationSummaryUpdate(ConversationId(id), ConversationSummary(title)))
        },
        onPinConversation = { id, pinned -> management.setPinned(ConversationId(id), pinned) },
        onForkConversation = management.takeIf { it.supportsBranching }?.let { branching ->
            { id, name -> branching.forkConversation(DesktopBranchOrigin(id, focus.selectedAgentId), name) }
        },
        onOpenCanvas = { context.openCanvas(it) },
        onArchiveCanvas = canvasShell.library::setArchived,
        conversationMarks = management.marks.rowActions(),
        chatOptions = desktopChatOptionsActions(chatController, setFontScale),
    )
}

/** Settle and marker for the sidebar's rows; null when this desktop keeps no marks. */
internal fun DesktopConversationMarks.rowActions(): ShellConversationMarkActions? =
    if (!enabled) null else ShellConversationMarkActions(onSettle = ::setSettled, onSetMarker = ::setMarker, markerChoices = markerChoices)

/** What the Chat options dialog changes on the desktop. */
private fun desktopChatOptionsActions(controller: DesktopChatController, setFontScale: (Float) -> Unit): ShellChatOptionsActions {
    val marks = controller.conversationManagement.marks
    return ShellChatOptionsActions(
        onDisplayChange = marks::setDisplayOptions,
        onTextSizeChange = { size -> setFontScale(size.fontScale) },
        onAutoPinNewChatsChange = marks::setAutoPinNewChats,
        onDownloadTranscript = { downloadTranscript(controller) },
    )
}

private fun downloadTranscript(controller: DesktopChatController) {
    val options = controller.conversationManagement.marks.displayOptions.value
    val transcript = DesktopTranscriptExport.transcriptOf(controller.state.value, options) ?: return
    runCatching { DesktopTranscriptExport.save(transcript) }
        .onFailure { controller.conversationManagement.reportError("Couldn't save the transcript: ${it.message ?: it::class.simpleName}") }
}

internal fun ConversationArchiveFilter.toShellArchiveFilter(): ShellArchiveFilter = when (this) {
    ConversationArchiveFilter.Active -> ShellArchiveFilter.Active
    ConversationArchiveFilter.Archived -> ShellArchiveFilter.Archived
    ConversationArchiveFilter.All -> ShellArchiveFilter.All
}

internal fun ShellArchiveFilter.toConversationArchiveFilter(): ConversationArchiveFilter = when (this) {
    ShellArchiveFilter.Active -> ConversationArchiveFilter.Active
    ShellArchiveFilter.Archived -> ConversationArchiveFilter.Archived
    ShellArchiveFilter.All -> ConversationArchiveFilter.All
}

/** Maps a lens nav item to its concrete desktop destination + icon for the mode. */
internal fun lensNavTarget(
    mode: WorkPlayMode,
    destination: LensDestination,
): Pair<DesktopDestination, ImageVector> = when (destination) {
    LensDestination.Memory -> DesktopDestination.Memory
    LensDestination.Schedules -> DesktopDestination.Schedules
    LensDestination.Channels -> DesktopDestination.Channels
    LensDestination.Skills -> DesktopDestination.Agents
    LensDestination.Conversations -> DesktopDestination.Conversations
} to shellSectionIcon(mode, destination)
