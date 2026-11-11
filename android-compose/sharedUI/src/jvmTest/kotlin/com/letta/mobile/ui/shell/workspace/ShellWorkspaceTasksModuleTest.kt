@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.shell.layout.edit.ShellLayoutTool
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.api.SubagentScopeAwareChannelTransport
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.ui.chat.subagents.SUBAGENT_ROSTER_EMPTY_TAG
import com.letta.mobile.ui.chat.subagents.SubagentRosterTag
import com.letta.mobile.ui.shell.modules.ShellModuleTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.theme.LettaDimens
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val Page = "tasks-module-page"
private const val HostPane = "tasks-module-host-pane"
private const val Agent = "agent-a"
private const val Open = "conv-open"
private const val Other = "conv-other"
private const val Indexing = "tool-indexing"
private const val Tests = "tool-tests"
private const val Elsewhere = "tool-elsewhere"
private const val TimeoutMillis = 10_000L
private val HostPaneWidth = 100.dp
private val WindowHeight = 800.dp
private val PageSlot = ModuleSlot(ShellViews.PAGE)
private val HostPaneSlot = ModuleSlot(ShellViews.SIDE_PANE)
private val TasksSlot = ModuleSlot(ShellViews.TASKS)
private val OpenScope = SubagentParentScope(Agent, Open)

/**
 * The background tasks are a module like any other: a layout (the person's, or an agent's through
 * `shell_layout`) that places the tasks view shows the open conversation's tasks in its lane, at the
 * size the lane gives it, through the host's shared subagent registry. Dismiss is the registry's
 * durable dismissal, so a dismissed task stays gone across a reconnect and when the module is
 * mounted again. The default layout has none; a compact window leaves it out; a host without a
 * registry shows the shell's unavailable module.
 */
class ShellWorkspaceTasksModuleTest {
    private val host = TaskHost()
    // Unconfined: a registry sees each connection state the moment it is set, so a dropped and
    // restored connection is never conflated into no change.
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val registries = mutableListOf<SubagentRepository>()

    init {
        host.seed(running(Indexing, Open), running(Tests, Open), running(Elsewhere, Other))
    }

    @AfterTest
    fun tearDown() {
        registries.forEach { it.close() }
        owner.cancel()
    }

    private fun registry(): SubagentRepository = SubagentRepository(host, owner).also { registries += it }

    private fun state(lanes: ShellLayoutConfig, open: SubagentParentScope? = OpenScope) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf(Agent to "Ada"), focus = ShellRailFocus(selectedAgentId = Agent)),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = Agent)),
            openConversation = open,
        ),
        layout = ShellWorkspaceLayout(lanes = lanes),
    )

    private fun shellHost() = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(
            main = { modifier -> Box(modifier.testTag(Page)) },
            agentCard = {},
            sidePane = { Box(Modifier.width(HostPaneWidth).fillMaxHeight().testTag(HostPane)) },
            overlays = {},
        ),
        chrome = ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer(),
        events = ShellWorkspaceEvents(),
    )

    private fun lanes(main: List<ModuleSlot> = listOf(PageSlot), side: List<ModuleSlot> = listOf(HostPaneSlot)) =
        ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))

    /** A window exactly [width] wide showing the workspace with [config], tasks listed through [tasks]. */
    private fun window(
        width: Dp,
        config: ShellLayoutConfig,
        tasks: ISubagentRepository? = registry(),
        open: SubagentParentScope? = OpenScope,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
        val shell = shellHost()
        setContent {
            ProvideShellTaskRegistry(tasks) {
                LettaShellWorkspace(state(config, open), shell, Modifier.size(width = width, height = WindowHeight))
            }
        }
        block()
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertDp(expected: Dp, actual: Dp, what: String) =
        assertEquals(expected.value, actual.value, absoluteTolerance = 1f, message = what)

    private fun ComposeUiTest.exists(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.awaitTask(id: String) = waitUntil(timeoutMillis = TimeoutMillis) { exists(task(id)) }

    private fun task(id: String) = SubagentRosterTag.RUNNING.of(id)

    private fun ComposeUiTest.dismiss(id: String) {
        onNode(hasText("Dismiss") and hasAnyAncestor(hasTestTag(task(id))), useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = TimeoutMillis) { !exists(task(id)) }
    }

    /** The open conversation's tasks are in the module, and the other conversation's are not. */
    private fun ComposeUiTest.assertListsTheOpenConversation() {
        awaitTask(Indexing)
        awaitTask(Tests)
        onNodeWithTag(task(Elsewhere)).assertDoesNotExist()
        val inModule = hasAnyAncestor(hasTestTag(SHELL_TASKS_MODULE_TAG))
        onNode(hasTestTag(task(Indexing)) and inModule).assertExists()
        onNode(hasText("Background tasks · 2 running") and inModule).assertExists()
    }

    private fun theDefaultHasNoTasks(width: Dp) = window(width, ShellLayoutConfig.Default) {
        waitForIdle()
        assertDp(width - HostPaneWidth, bounds(Page).right, "the page ends where the host pane starts at $width")
        assertDp(width, bounds(HostPane).right, "the host pane ends at the window edge at $width")
        onNodeWithTag(SHELL_TASKS_MODULE_TAG).assertDoesNotExist()
        assertEquals(0, host.fetches.get(), "the default layout lists no tasks at $width")
    }

    @Test
    fun theDefaultLayoutHasNoTasksOnAPhone() = theDefaultHasNoTasks(360.dp)

    @Test
    fun theDefaultLayoutHasNoTasksJustBelowTheWideBreakpoint() = theDefaultHasNoTasks(700.dp)

    @Test
    fun theDefaultLayoutHasNoTasksJustAboveTheWideBreakpoint() = theDefaultHasNoTasks(760.dp)

    @Test
    fun theDefaultLayoutHasNoTasksOnADesktop() = theDefaultHasNoTasks(1200.dp)

    private fun sideTasksSitAfterThePage(width: Dp) = window(width, lanes(side = listOf(TasksSlot, HostPaneSlot))) {
        assertListsTheOpenConversation()
        val module = bounds(SHELL_TASKS_MODULE_TAG)
        assertDp(LettaDimens.Pane.sidePanelWidth, module.width, "the module is a side panel wide at $width")
        assertDp(width - HostPaneWidth, module.right, "it runs up to the host pane at $width")
        assertDp(WindowHeight, module.bottom, "it runs to the bottom at $width")
        assertTrue(bounds(Page).right <= module.left, "the page ends before it at $width")
    }

    @Test
    fun tasksPlacedInTheSideLaneListTheOpenConversationOnADesktop() = sideTasksSitAfterThePage(1200.dp)

    @Test
    fun tasksPlacedInTheSideLaneListTheOpenConversationJustAboveTheWideBreakpoint() = sideTasksSitAfterThePage(760.dp)

    @Test
    fun tasksShareTheMainLaneWithThePage() = window(1200.dp, lanes(main = listOf(PageSlot, TasksSlot))) {
        assertListsTheOpenConversation()
        val page = bounds(Page)
        val module = bounds(SHELL_TASKS_MODULE_TAG)
        assertDp(page.width, module.width, "the page and the tasks share the lane equally")
        assertTrue(module.left >= page.right, "the tasks sit after the page")
    }

    private fun aCompactWindowLeavesTasksOut(width: Dp, config: ShellLayoutConfig) = window(width, config) {
        waitForIdle()
        assertDp(width - HostPaneWidth, bounds(Page).right, "the page runs to the host pane at $width")
        onNodeWithTag(SHELL_TASKS_MODULE_TAG).assertDoesNotExist()
        assertEquals(0, host.fetches.get(), "a module left out of a compact window lists nothing at $width")
    }

    @Test
    fun aPhoneLeavesSideTasksOut() = aCompactWindowLeavesTasksOut(360.dp, lanes(side = listOf(TasksSlot, HostPaneSlot)))

    @Test
    fun aPhoneShowsThePageAloneInTheMainLane() = aCompactWindowLeavesTasksOut(360.dp, lanes(main = listOf(TasksSlot, PageSlot)))

    @Test
    fun aWindowJustBelowTheWideBreakpointLeavesSideTasksOut() =
        aCompactWindowLeavesTasksOut(700.dp, lanes(side = listOf(TasksSlot, HostPaneSlot)))

    @Test
    fun aHostWithoutARegistryShowsTheModuleAsUnavailable() = window(1200.dp, lanes(side = listOf(TasksSlot)), tasks = null) {
        onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
        onNodeWithTag(SHELL_TASKS_MODULE_TAG).assertDoesNotExist()
        onNodeWithTag(Page).assertIsDisplayed()
    }

    @Test
    fun noOpenConversationShowsTheEmptyState() = window(1200.dp, lanes(side = listOf(TasksSlot)), open = null) {
        onNode(hasTestTag(SUBAGENT_ROSTER_EMPTY_TAG) and hasAnyAncestor(hasTestTag(SHELL_TASKS_MODULE_TAG))).assertExists()
        assertEquals(0, host.fetches.get(), "nothing is listed without an open conversation")
    }

    @Test
    fun aDismissedTaskStaysGoneAcrossAReconnectAndARemount() = runDesktopComposeUiTest(width = 1200, height = 800) {
        var config by mutableStateOf(lanes(side = listOf(TasksSlot)))
        var tasks: ISubagentRepository by mutableStateOf(registry())
        val shell = shellHost()
        setContent {
            ProvideShellTaskRegistry(tasks) {
                LettaShellWorkspace(state(config), shell, Modifier.size(width = 1200.dp, height = WindowHeight))
            }
        }
        assertListsTheOpenConversation()

        dismiss(Indexing)
        assertEquals(listOf(OpenScope to Indexing), host.dismissed.toList(), "the dismissal went to the host for the open conversation")
        awaitTask(Tests)

        val fetched = host.fetches.get()
        host.reconnect()
        waitUntil(timeoutMillis = TimeoutMillis) { host.fetches.get() > fetched }
        waitForIdle()
        onNodeWithTag(task(Indexing)).assertDoesNotExist()
        awaitTask(Tests)

        config = lanes()
        waitForIdle()
        onNodeWithTag(SHELL_TASKS_MODULE_TAG).assertDoesNotExist()
        tasks = registry()
        config = lanes(side = listOf(TasksSlot))
        awaitTask(Tests)
        onNodeWithTag(task(Indexing)).assertDoesNotExist()
    }

    /** An agent mounts the module through `shell_layout`; the person dismisses a task from it. */
    @Test
    fun anAgentMountsTheTasksModuleAndATaskIsDismissedFromIt() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val port = ShellLayoutAgentPort()
        val tool = ShellLayoutTool(port)
        val settings = InMemorySecureSettingsStore()
        val tasks = registry()
        val shell = shellHost()
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                ProvideShellTaskRegistry(tasks) {
                    val controller = rememberShellLayoutController(null, settings)
                    val size = Modifier.size(width = 1200.dp, height = WindowHeight)
                    LettaShellWorkspace(state(controller.workspaceLayout().lanes), shell, size)
                }
            }
        }
        waitForIdle()
        onNodeWithTag(SHELL_TASKS_MODULE_TAG).assertDoesNotExist()

        val input = Json.parseToJsonElement("""{"action":"apply","ops":[{"op":"add","lane":"side","view":"shell.tasks","index":0}]}""")
        val result = runBlocking { tool.invoke(input.jsonObject, ExternalToolCaller(agentId = Agent)) }
        assertIs<ExternalToolResult.Success>(result, "the agent's edit applies")
        assertListsTheOpenConversation()
        waitUntil(timeoutMillis = TimeoutMillis) { onAllNodesWithText("Layout changed by Ada").fetchSemanticsNodes().isNotEmpty() }

        dismiss(Tests)
        awaitTask(Indexing)
        assertEquals(listOf(OpenScope to Tests), host.dismissed.toList())
    }

    private fun running(id: String, conversation: String) = SubagentEntry(
        toolCallId = id,
        description = "Task $id",
        status = SubagentStatus.RUNNING,
        parentAgentId = Agent,
        parentConversationId = conversation,
    )
}

/**
 * A host whose task list is scoped by conversation and whose dismissal is durable: a dismissed task
 * is never listed again, whichever registry asks. [reconnect] drops and restores the connection.
 */
private class TaskHost : NoOpChannelTransport(), SubagentScopeAwareChannelTransport {
    override val state = MutableStateFlow<ChannelTransportState>(connected())
    override val events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 16)

    private val entries = CopyOnWriteArrayList<SubagentEntry>()

    /** The dismissals the host received, in order. */
    val dismissed = CopyOnWriteArrayList<Pair<SubagentParentScope, String>>()

    /** How many scoped lists the host answered. */
    val fetches = AtomicInteger()

    fun seed(vararg seeded: SubagentEntry) {
        entries += seeded
    }

    fun reconnect() {
        state.value = ChannelTransportState.Disconnected(1006, "dropped")
        state.value = connected()
    }

    override suspend fun sendSubagentListForScope(
        scope: SubagentParentScope,
        all: Boolean,
        timeoutMs: Long,
    ): ServerFrame.SubagentListResponse {
        fetches.incrementAndGet()
        val listed = entries.filter { it.inScope(scope) && dismissed.none { (_, id) -> id == it.toolCallId } }
        return ServerFrame.SubagentListResponse(id = "list", ts = "t", success = true, subagents = listed)
    }

    override suspend fun dismissSubagent(scope: SubagentParentScope, toolCallId: ToolCallId): Result<List<SubagentEntry>> {
        val entry = entries.singleOrNull { it.inScope(scope) && it.toolCallId == toolCallId.value }
            ?: return Result.failure(IllegalArgumentException("unknown scoped dispatch"))
        dismissed += scope to toolCallId.value
        return Result.success(listOf(entry.copy(status = SubagentStatus.CANCELLED, terminalReason = "user_dismissed")))
    }

    private fun SubagentEntry.inScope(scope: SubagentParentScope) =
        parentAgentId == scope.parentAgentId && parentConversationId == scope.parentConversationId

    private fun connected() = ChannelTransportState.Connected(serverId = "srv", sessionId = "sess", deviceId = "dev")
}
