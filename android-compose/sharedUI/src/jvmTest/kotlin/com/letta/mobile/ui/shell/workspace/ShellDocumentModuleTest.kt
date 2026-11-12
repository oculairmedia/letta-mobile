@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpener
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceSessions
import com.letta.mobile.data.canvas.workspace.SharedNote
import com.letta.mobile.data.canvas.workspace.SharedNoteDirectory
import com.letta.mobile.data.canvas.workspace.SharedNoteListing
import com.letta.mobile.data.canvas.workspace.SharedNotebooks
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.shell.modules.ShellModuleTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelTags
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.theme.LettaDimens
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val Page = "document-module-page"
private const val TimeoutMillis = 10_000L
private val WindowHeight = 800.dp
private val PageSlot = ModuleSlot(ShellViews.PAGE)
private val DocumentSlot = ModuleSlot(ShellViews.DOCUMENT)
private val Note = NotebookDocumentId("0123456789abcdef0123456789abcdef")

/**
 * The way into a note the host shares with this device: the shared-notes view is a shell module
 * beside the main agent's chat. It lists what the host shares now, opens a note into the shared
 * document workspace (an edit there reaches the host's note session), marks a note whose grant is
 * withdrawn while open and lets the person leave it, and says so honestly when the host shares
 * nothing or cannot be reached. A wide window draws it in the side lane after the page; a compact
 * one draws it over the page, which stays mounted beneath.
 */
class ShellDocumentModuleTest {
    private val host = NoteHost()

    private fun state(lanes: ShellLayoutConfig) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
            openConversation = SubagentParentScope("agent-a", "conv-a"),
        ),
        layout = ShellWorkspaceLayout(lanes = lanes),
    )

    private fun shellHost() = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(main = { modifier -> Box(modifier.testTag(Page)) }, agentCard = {}, sidePane = {}, overlays = {}),
        chrome = ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer(),
        events = ShellWorkspaceEvents(),
    )

    private fun lanes(main: List<ModuleSlot> = listOf(PageSlot), side: List<ModuleSlot> = listOf(DocumentSlot)) =
        ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))

    private fun window(width: Dp, notebooks: SharedNotebooks? = host, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
            val shell = shellHost()
            setContent {
                ProvideShellSharedNotes(notebooks) {
                    LettaShellWorkspace(state(lanes()), shell, Modifier.size(width = width, height = WindowHeight))
                }
            }
            block()
        }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.await(tag: String) = waitUntil(timeoutMillis = TimeoutMillis) { exists(tag) }

    private fun ComposeUiTest.openTheSharedNote() {
        await(ShellDocumentTags.row(SharedNote(Note, "Plan")))
        onNodeWithTag(ShellDocumentTags.row(SharedNote(Note, "Plan"))).performClick()
        await("notebook-note-markdown")
        onNode(hasTestTag("document-workspace") and hasAnyAncestor(hasTestTag(ShellDocumentTags.MODULE))).assertExists()
    }

    private fun opensBesideThePage(width: Dp) = window(width) {
        openTheSharedNote()
        val page = onNodeWithTag(Page).getUnclippedBoundsInRoot()
        val module = onNodeWithTag(ShellDocumentTags.MODULE).getUnclippedBoundsInRoot()
        assertEquals(LettaDimens.Pane.sidePanelWidth.value, (module.right - module.left).value, absoluteTolerance = 1f)
        assertTrue(page.right <= module.left, "the chat page keeps its place before the view at $width")
        onNodeWithTag(Page).assertIsDisplayed()
    }

    @Test
    fun aSharedNoteOpensBesideTheChatOnADesktop() = opensBesideThePage(1200.dp)

    @Test
    fun aSharedNoteOpensBesideTheChatJustAboveTheWideBreakpoint() = opensBesideThePage(760.dp)

    private fun opensOverThePage(width: Dp) = window(width) {
        openTheSharedNote()
        val module = onNodeWithTag(ShellDocumentTags.MODULE).getUnclippedBoundsInRoot()
        val spanned = (module.right - module.left).value
        assertEquals(width.value, spanned, absoluteTolerance = 1f, message = "the view spans the window at $width")
        onNodeWithTag(Page).assertExists()
    }

    @Test
    fun onAPhoneASharedNoteOpensOverTheChatWhichStaysMounted() = opensOverThePage(360.dp)

    @Test
    fun justBelowTheWideBreakpointASharedNoteOpensOverTheChat() = opensOverThePage(700.dp)

    @Test
    fun anEditInTheWorkspaceReachesTheHostsNote() = window(1200.dp) {
        openTheSharedNote()
        onNodeWithTag("notebook-note-markdown").performTextReplacement("edited from the shell")
        onNode(hasText("Save") and hasAnyAncestor(hasTestTag(ShellDocumentTags.MODULE))).performClick()
        waitUntil(timeoutMillis = TimeoutMillis) { host.markdown[Note] == "edited from the shell" }
    }

    @Test
    fun aGrantRevokedWhileOpenIsMarkedAndThePersonCanLeave() = window(1200.dp) {
        openTheSharedNote()
        onNodeWithTag("notebook-note-markdown").performTextReplacement("draft after revoke")
        host.granted.clear()
        onNode(hasText("Save") and hasAnyAncestor(hasTestTag(ShellDocumentTags.MODULE))).performClick()
        await(ShellDocumentTags.WITHDRAWN)

        onNodeWithTag(ShellDocumentTags.BACK).performClick()
        await(ShellDocumentTags.EMPTY)
        onNodeWithTag("document-workspace").assertDoesNotExist()
        assertEquals("shared text", host.markdown[Note], "nothing reached the host after the revoke")
    }

    @Test
    fun aHostSharingNothingShowsTheEmptyState() {
        host.granted.clear()
        window(1200.dp) {
            await(ShellDocumentTags.EMPTY)
            onNodeWithTag(ShellDocumentTags.LIST).assertDoesNotExist()
        }
    }

    @Test
    fun anUnreachableHostSaysSoAndTryAgainListsOnceItAnswers() {
        host.reachable = false
        window(1200.dp) {
            await(ShellDocumentTags.UNAVAILABLE)
            host.reachable = true
            onNode(hasText("Try again") and hasAnyAncestor(hasTestTag(ShellDocumentTags.UNAVAILABLE))).performClick()
            await(ShellDocumentTags.row(SharedNote(Note, "Plan")))
        }
    }

    @Test
    fun aHostThatCannotReachSharedNotesShowsTheViewAsUnavailable() = window(1200.dp, notebooks = null) {
        onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
        onNodeWithTag(ShellDocumentTags.MODULE).assertDoesNotExist()
        onNodeWithTag(Page).assertIsDisplayed()
    }

    /** The person shows the view from the agent's menu beside the chat, and hides it from the view itself. */
    @Test
    fun thePersonShowsTheViewFromTheAgentMenuAndClosesIt() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val port = ShellLayoutAgentPort()
        val settings = InMemorySecureSettingsStore()
        val shell = shellHost()
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                ProvideShellSharedNotes(host) {
                    val controller = rememberShellLayoutController(null, settings)
                    LettaShellWorkspace(state(controller.workspaceLayout().lanes), shell, Modifier.size(1200.dp, WindowHeight))
                }
            }
        }
        waitForIdle()
        onNodeWithTag(ShellDocumentTags.MODULE).assertDoesNotExist()

        onNodeWithTag(ShellAgentPanelTags.AGENT_MENU).performClick()
        onNode(hasText("Shared notes")).performClick()
        await(ShellDocumentTags.row(SharedNote(Note, "Plan")))
        onNodeWithTag(Page).assertIsDisplayed()

        onNodeWithTag(ShellDocumentTags.CLOSE).performClick()
        waitUntil(timeoutMillis = TimeoutMillis) { !exists(ShellDocumentTags.MODULE) }
        onNodeWithTag(Page).assertIsDisplayed()
    }
}

/** A host holding one note; [granted] and [reachable] decide every call, as the real host does. */
private class NoteHost : SharedNotebooks, NotebookNoteBackend {
    val markdown = ConcurrentHashMap(mapOf(Note to "shared text"))
    val granted: MutableSet<NotebookDocumentId> = ConcurrentHashMap.newKeySet<NotebookDocumentId>().apply { add(Note) }

    @Volatile
    var reachable = true

    override val directory = SharedNoteDirectory {
        if (reachable) SharedNoteListing.Listed(granted.map { SharedNote(it, "Plan") }) else SharedNoteListing.Unavailable("down")
    }

    override val opener = DocumentWorkspaceOpener { id ->
        when (load(id)) {
            is NotebookNoteLoad.Found -> DocumentWorkspaceOpen.Opened(DocumentWorkspaceSessions(NotebookNoteSession(id, this), null))
            NotebookNoteLoad.Denied -> DocumentWorkspaceOpen.Denied
            else -> DocumentWorkspaceOpen.Unavailable
        }
    }

    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad = when {
        !reachable -> NotebookNoteLoad.Unavailable("down")
        id !in granted -> NotebookNoteLoad.Denied
        else -> markdown[id]?.let { NotebookNoteLoad.Found(NotebookNoteSnapshot(id, "Plan", it)) } ?: NotebookNoteLoad.NotFound
    }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
        val current = when (val loaded = load(id)) {
            is NotebookNoteLoad.Found -> loaded.snapshot
            NotebookNoteLoad.Denied -> return NotebookNoteSave.Denied
            else -> return NotebookNoteSave.Unavailable("down")
        }
        if (current.markdown != expected) return NotebookNoteSave.Conflict(current)
        markdown[id] = replacement
        return NotebookNoteSave.Saved(current.copy(markdown = replacement))
    }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave =
        if (applied.id in granted) NotebookNoteSave.Saved(applied) else NotebookNoteSave.Denied
}
