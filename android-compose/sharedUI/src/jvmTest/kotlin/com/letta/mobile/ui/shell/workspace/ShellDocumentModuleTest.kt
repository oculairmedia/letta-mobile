@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
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
import com.letta.mobile.data.canvas.workspace.SharedNotesCursor
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.layout.edit.SharedNotesPlacement
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.components.LettaRefreshTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val Page = "document-module-page"
private const val TimeoutMillis = 10_000L
private val WindowHeight = 800.dp
private val MaxShowMoreHeight = 64.dp
private val PageSlot = ModuleSlot(ShellViews.PAGE)
private val DocumentSlot = ModuleSlot(ShellViews.DOCUMENT)
private val Note = NotebookDocumentId("0123456789abcdef0123456789abcdef")
private val Later = NotebookDocumentId("fedcba9876543210fedcba9876543210")

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

    private fun state(lanes: ShellLayoutConfig) = state(ShellWorkspaceLayout(lanes = lanes))

    private fun state(layout: ShellWorkspaceLayout) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
            openConversation = SubagentParentScope("agent-a", "conv-a"),
        ),
        layout = layout,
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

    /** The input host the window runs on: touch (a phone, the default) or pointer (a desktop). */
    private var input = LettaHost.Touch

    private fun window(
        width: Dp,
        notebooks: SharedNotebooks? = host,
        layout: ShellLayoutConfig = lanes(),
        block: ComposeUiTest.() -> Unit,
    ) =
        runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
            val shell = shellHost()
            setContent {
                ProvideLettaHost(input) {
                    ProvideShellSharedNotes(notebooks) {
                        LettaShellWorkspace(state(layout), shell, Modifier.size(width = width, height = WindowHeight))
                    }
                }
            }
            block()
        }

    private val inTheView = hasAnyAncestor(hasTestTag(ShellDocumentTags.MODULE))
    private val theRefresh = hasContentDescription("Refresh") and inTheView

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

    /** An agent may place the view in the main lane; a phone, whose main lane draws only the page, still shows it. */
    @Test
    fun onAPhoneTheViewPlacedInTheMainLaneStillShowsOverTheChat() =
        window(360.dp, layout = lanes(main = listOf(PageSlot, DocumentSlot), side = emptyList())) {
            openTheSharedNote()
            val module = onNodeWithTag(ShellDocumentTags.MODULE).getUnclippedBoundsInRoot()
            assertEquals(360f, (module.right - module.left).value, absoluteTolerance = 1f, message = "the view spans the phone")
            assertEquals(1, onAllNodesWithTag(ShellDocumentTags.MODULE).fetchSemanticsNodes().size, "drawn once")
            onNodeWithTag(Page).assertExists()
        }

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

    /**
     * A host with more notes than one page shows "Show more" at the end of the list, on one line;
     * it appends the next page, says so when that page cannot be loaded and keeps what is shown.
     */
    private fun showMoreLoadsTheNextPage(width: Dp) {
        host.granted.add(Later)
        host.pageSize = 1
        window(width) {
            await(ShellDocumentTags.MORE)
            onNodeWithTag(ShellDocumentTags.row(SharedNote(Note, "Plan"))).assertExists()
            onNodeWithTag(ShellDocumentTags.row(SharedNote(Later, "Plan"))).assertDoesNotExist()
            val more = onNodeWithTag(ShellDocumentTags.MORE).getUnclippedBoundsInRoot()
            assertTrue((more.bottom - more.top) < MaxShowMoreHeight, "Show more stays one line at $width")

            host.reachable = false
            onNodeWithTag(ShellDocumentTags.MORE).performClick()
            await(ShellDocumentTags.MORE_FAILED)
            onNodeWithTag(ShellDocumentTags.row(SharedNote(Note, "Plan"))).assertExists()

            host.reachable = true
            onNodeWithTag(ShellDocumentTags.MORE).performClick()
            await(ShellDocumentTags.row(SharedNote(Later, "Plan")))
            waitUntil(timeoutMillis = TimeoutMillis) { !exists(ShellDocumentTags.MORE) }
            onNodeWithTag(ShellDocumentTags.MORE_FAILED).assertDoesNotExist()
            onNodeWithTag(ShellDocumentTags.row(SharedNote(Note, "Plan"))).assertExists()
        }
    }

    @Test
    fun onAPhoneShowMoreLoadsTheNextPage() = showMoreLoadsTheNextPage(360.dp)

    @Test
    fun onADesktopShowMoreLoadsTheNextPage() = showMoreLoadsTheNextPage(1200.dp)

    /**
     * The list reloads the shared way (LettaRefreshable): on a pointer host through the header's one
     * refresh, with no pull; on touch by a pull on the list, with no refresh button. Either way a note
     * the host started sharing since the list was asked for appears.
     */
    @Test
    fun onAPointerHostTheHeadersRefreshReloadsTheList() {
        input = LettaHost.Pointer
        window(1200.dp) {
            await(ShellDocumentTags.row(SharedNote(Note, "Plan")))
            onNode(hasTestTag(LettaRefreshTags.PULL) and inTheView).assertDoesNotExist()
            host.granted.add(Later)
            onNode(theRefresh).performClick()
            await(ShellDocumentTags.row(SharedNote(Later, "Plan")))
        }
    }

    private fun aPullReloadsTheList(width: Dp) = window(width) {
        await(ShellDocumentTags.row(SharedNote(Note, "Plan")))
        onNode(theRefresh).assertDoesNotExist()
        host.granted.add(Later)
        onNode(hasTestTag(LettaRefreshTags.PULL) and inTheView).performTouchInput {
            swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600)
        }
        await(ShellDocumentTags.row(SharedNote(Later, "Plan")))
    }

    @Test
    fun onAPhoneAPullReloadsTheList() = aPullReloadsTheList(360.dp)

    @Test
    fun onATouchTabletAPullReloadsTheList() = aPullReloadsTheList(760.dp)

    /** A pull reaches the refresh from a state with no rows, too: an unreachable host lists once a pull asks again. */
    @Test
    fun onTouchAPullOnTheUnreachableStateAsksAgain() {
        host.reachable = false
        window(360.dp) {
            await(ShellDocumentTags.UNAVAILABLE)
            host.reachable = true
            onNode(hasTestTag(LettaRefreshTags.PULL) and inTheView).performTouchInput {
                swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600)
            }
            await(ShellDocumentTags.row(SharedNote(Note, "Plan")))
        }
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
                    LettaShellWorkspace(state(controller.workspaceLayout()), shell, Modifier.size(1200.dp, WindowHeight))
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

    /** Two windows, two layouts: the menu in the first window edits the first window's layout, not the one attached last. */
    @Test
    fun eachWindowsMenuTogglesItsOwnLayoutNotTheLastAttachedOne() = runDesktopComposeUiTest(width = 2400, height = 800) {
        val port = ShellLayoutAgentPort()
        val first = ShellLayoutConfigStore(InMemorySecureSettingsStore(), "shell.first.lanes")
        val second = ShellLayoutConfigStore(InMemorySecureSettingsStore(), "shell.second.lanes")
        port.attach(first)
        port.attach(second)
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                ProvideShellSharedNotes(host) {
                    Row {
                        for (store in listOf(first, second)) {
                            val layout = ShellWorkspaceLayout(lanes = store.config.collectAsState().value, lanesStore = store)
                            LettaShellWorkspace(state(layout), shellHost(), Modifier.size(1200.dp, WindowHeight))
                        }
                    }
                }
            }
        }
        waitForIdle()

        onAllNodesWithTag(ShellAgentPanelTags.AGENT_MENU)[0].performClick()
        onNode(hasText("Shared notes")).performClick()
        waitForIdle()

        assertTrue(SharedNotesPlacement.shown(first.config.value), "the first window's own layout shows the view")
        assertFalse(SharedNotesPlacement.shown(second.config.value), "the window attached last is untouched")
        assertEquals(second, port.attached.value)
    }

    /** A full side lane refuses the view; the person is told why and how to make room instead of nothing happening. */
    @Test
    fun aFullSideLaneTellsThePersonToCloseAViewFirst() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val store = ShellLayoutConfigStore(InMemorySecureSettingsStore(), "shell.full.lanes")
        val sideSlots = listOf(ModuleSlot(ShellViews.SIDE_PANE), ModuleSlot(ShellViews.TASKS), ModuleSlot(ShellViews.NOTE, "board"))
        val full = lanes(side = sideSlots)
        store.commit(full)
        val before = store.config.value
        setContent {
            ProvideShellSharedNotes(host) {
                val layout = ShellWorkspaceLayout(lanes = store.config.collectAsState().value, lanesStore = store)
                LettaShellWorkspace(state(layout), shellHost(), Modifier.size(1200.dp, WindowHeight))
            }
        }
        waitForIdle()

        onNodeWithTag(ShellAgentPanelTags.AGENT_MENU).performClick()
        onNode(hasText("Shared notes")).performClick()

        waitUntil(timeoutMillis = TimeoutMillis) {
            onAllNodes(hasText("Close one of them to make room for Shared notes", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasText("already holds 3 views", substring = true)).assertExists()
        assertEquals(before, store.config.value, "nothing was dropped to make room")
        onNodeWithTag(ShellDocumentTags.MODULE).assertDoesNotExist()
    }
}

/** A host holding one note; [granted] and [reachable] decide every call, as the real host does. */
private class NoteHost : SharedNotebooks, NotebookNoteBackend {
    val markdown = ConcurrentHashMap(mapOf(Note to "shared text"))
    val granted: MutableSet<NotebookDocumentId> = ConcurrentHashMap.newKeySet<NotebookDocumentId>().apply { add(Note) }

    @Volatile
    var reachable = true

    /** How many notes one page holds; the cursor is the index the next page starts at. */
    @Volatile
    var pageSize = Int.MAX_VALUE

    override val directory = SharedNoteDirectory { from ->
        if (reachable) page(from?.value?.toInt() ?: 0) else SharedNoteListing.Unavailable("down")
    }

    private fun page(start: Int): SharedNoteListing.Listed {
        val ordered = granted.sortedBy { it.value }
        val end = minOf(ordered.size, start + minOf(pageSize, ordered.size))
        val next = SharedNotesCursor(end.toString()).takeIf { end < ordered.size }
        return SharedNoteListing.Listed(ordered.subList(start, end).map { SharedNote(it, "Plan") }, next)
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
