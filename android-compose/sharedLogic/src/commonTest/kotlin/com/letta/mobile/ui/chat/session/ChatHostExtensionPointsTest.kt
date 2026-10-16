package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.presence.ConversationRunState
import com.letta.mobile.data.runtime.LiveNotice
import com.letta.mobile.data.runtime.NoticeLevel
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-by4vm.98.3: composer host inputs, A2UI, branching, recompile and live status. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHostExtensionPointsTest {
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Nora", conversationId = "conv-1")

    @Test
    fun hostCommandsShowInTheComposerAndRunTheirAction() = runTest {
        var ran = 0
        val inputs = MutableStateFlow(
            ChatComposerHostInputs(
                commands = listOf(
                    ChatHostCommand(label = "canvas", description = "Open canvas", run = { ran++ }),
                    ChatHostCommand(label = "skill", description = "Fill", fillsComposer = true, run = {}),
                ),
                placeholder = "Ask Nora",
            ),
        )
        val port = port(TimelineChatSessionOptions(composerHost = inputs))
        runCurrent()

        val commands = port.composer.value.commands
        assertEquals(listOf("canvas", "skill"), commands.map { it.id })
        assertEquals(listOf(false, true), commands.map { it.fillsComposer })
        assertEquals("Ask Nora", port.composer.value.placeholder)

        port.actions.runComposerCommand(commands.first())
        assertEquals(1, ran)

        inputs.value = ChatComposerHostInputs()
        runCurrent()
        assertTrue(port.composer.value.commands.isEmpty())
        assertEquals(null, port.composer.value.placeholder)
    }

    @Test
    fun branchingFollowsTheHostsSupportAndPlansAgainstThePagesTimeline() = runTest {
        val branching = FakeBranching()
        val port = port(TimelineChatSessionOptions(branching = branching))
        runCurrent()
        assertFalse(port.capabilities.value.fork)

        branching.support.value = ChatBranchSupport(fork = true, nameForks = true)
        runCurrent()
        val capabilities = port.capabilities.value
        assertTrue(capabilities.fork && capabilities.editAndResend && capabilities.rerunInFork && capabilities.nameForks)

        val prompt = UiMessage(id = "m1", role = "user", content = "hi", timestamp = "t")
        port.actions.forkFromMessage(prompt, "spike")
        port.actions.editAndResend(prompt)
        port.actions.rerunInFork(prompt)
        assertEquals(listOf("fork:m1:spike", "edit:m1", "rerun:m1"), branching.calls)
    }

    @Test
    fun a2uiActionsGoToTheHost() = runTest {
        val seen = mutableListOf<A2uiAction>()
        val port = port(TimelineChatSessionOptions(onA2uiAction = { seen += it }))
        val action = A2uiAction(name = "pick", surfaceId = "surface-1")

        port.actions.submitA2uiAction(action)

        assertEquals(listOf(action), seen)
    }

    @Test
    fun recompileStatusAndDismissComeFromTheHost() = runTest {
        val recompile = ConversationRecompileController(backgroundScope, gateway = { null })
        val port = port(TimelineChatSessionOptions(recompile = recompile))

        recompile.recompile(ConversationRecompileRequest("conv-1", "agent-1"))
        assertTrue(port.recompileStatus.value is RecompileStatus.Unavailable)

        port.actions.dismissRecompileStatus()
        assertEquals(RecompileStatus.Idle, port.recompileStatus.value)
    }

    @Test
    fun liveStatusFollowsTheNamedConversationsRun() = runTest {
        val busy = RuntimeLiveStatus(notice = LiveNotice("compacting", NoticeLevel.Info))
        val runs = MutableStateFlow(mapOf("conv-1" to ConversationRunState("conv-1", "agent-1", live = busy)))
        val key = MutableStateFlow<String?>("conv-1")
        val port = port(TimelineChatSessionOptions(liveStatus = runLiveStatus(runs, key)))
        backgroundScope.launch { port.liveStatus.collect {} }
        runCurrent()
        assertEquals(busy, port.liveStatus.value)

        key.value = "conv-2"
        runCurrent()
        assertEquals(RuntimeLiveStatus.Idle, port.liveStatus.value)
    }

    private fun TestScope.port(options: TimelineChatSessionOptions): TimelineChatSessionPort {
        val port = TimelineChatSessionPort(FakeTimelineChatSession(), target, backgroundScope, options)
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        backgroundScope.launch { port.capabilities.collect {} }
        return port
    }
}

private class FakeBranching : ChatBranchingSource {
    override val support = MutableStateFlow(ChatBranchSupport())
    val calls = mutableListOf<String>()

    override fun forkFrom(message: UiMessage, view: ChatBranchView, name: String?) {
        calls += "fork:${message.id}:$name"
    }

    override fun editAndResend(message: UiMessage, view: ChatBranchView) {
        calls += "edit:${message.id}"
    }

    override fun rerun(message: UiMessage, view: ChatBranchView) {
        calls += "rerun:${message.id}"
    }
}
