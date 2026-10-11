package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.conversation
import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.prompt
import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.reply
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** letta-mobile-7mys7: the one fork / edit / rerun controller every surface drives. */
class ConversationBranchControllerTest {
    private val timeline = listOf(prompt("m1", "hello"), reply("m2", "hi"), prompt("m3", "bye"))
    private val source = BranchSource("conv-1", "agent-1", timeline)
    private val opened = mutableListOf<BranchOutcome>()
    private val errors = mutableListOf<String>()

    @Test
    fun anEditOpensTheForkWithThePromptAsItsDraftAndRecordsItsParent() = runTest(UnconfinedTestDispatcher()) {
        val lineage = ConversationLineage(MapStore())
        val controller = controller(branching(), lineage = lineage)

        assertTrue(controller.editAndResend(source, timeline[2]))

        val outcome = opened.single()
        assertEquals(BranchKind.Edit, outcome.kind)
        assertEquals("bye", outcome.draft?.text)
        assertFalse(outcome.sendNow)
        assertEquals(ConversationParent("conv-1", BranchKind.Edit), lineage.parentOf("fork-1"))
        assertEquals(BranchState.Idle, controller.state.value)
    }

    @Test
    fun aRerunOpensTheForkAndAsksForTheSend() = runTest(UnconfinedTestDispatcher()) {
        controller(branching()).rerun(source, timeline[2])

        val outcome = opened.single()
        assertEquals(BranchKind.Rerun, outcome.kind)
        assertTrue(outcome.sendNow)
        assertEquals("bye", outcome.draft?.text)
    }

    @Test
    fun aFailureIsReportedAndTheNextBranchRuns() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val lineage = ConversationLineage(MapStore())
        val controller = controller(
            ConversationBranching(BranchBackend(fork = { if (fail) error("host offline") else conversation("fork-2") }, createEmpty = { conversation("empty") })),
            lineage = lineage,
        )

        controller.fork(source, timeline[0])
        assertEquals(BranchState.Failed(BranchKind.Fork, "host offline"), controller.state.value)
        assertNull(lineage.parentOf("fork-2"), "a failed fork records nothing")

        fail = false
        controller.fork(source, timeline[0])

        assertEquals(listOf("host offline"), errors)
        assertEquals(listOf("fork-2"), opened.map { it.conversation.id.value })
        assertEquals(BranchState.Idle, controller.state.value)
    }

    @Test
    fun offlineFailsAtOnceWithoutCallingTheBackend() = runTest(UnconfinedTestDispatcher()) {
        var forks = 0
        val controller = controller(
            ConversationBranching(BranchBackend(fork = { forks++; conversation("fork") }, createEmpty = { conversation("empty") })),
            online = false,
        )

        assertFalse(controller.fork(source, timeline[0]))

        assertEquals(0, forks)
        assertEquals(listOf(ConversationBranchController.OFFLINE), errors)
        controller.dismissFailure()
        assertEquals(BranchState.Idle, controller.state.value)
    }

    @Test
    fun aBackendThatCannotForkSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val controller = ConversationBranchController(this, branching = { null }, opener = { opened += it }, onError = { errors += it })

        assertFalse(controller.supported)
        assertFalse(controller.fork(source, timeline[0]))
        assertEquals(listOf(ConversationBranchController.UNSUPPORTED), errors)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var forks = 0
        val controller = controller(
            ConversationBranching(BranchBackend(fork = { forks++; gate.await(); conversation("fork") }, createEmpty = { conversation("empty") })),
        )

        assertTrue(controller.fork(source, timeline[0]))
        assertEquals(BranchState.Working(BranchKind.Fork, "m1"), controller.state.value)
        assertFalse(controller.rerun(source, timeline[2]))
        gate.complete(Unit)

        assertEquals(1, forks)
        assertEquals(1, opened.size)
    }

    @Test
    fun aWholeConversationForkCanBeNamed() = runTest(UnconfinedTestDispatcher()) {
        val requests = mutableListOf<ConversationForkRequest>()
        val controller = controller(
            ConversationBranching(
                BranchBackend(
                    fork = { requests += it; conversation("fork") },
                    createEmpty = { conversation("empty") },
                    rename = { fork, name -> fork.copy(summary = name) },
                ),
            ),
        )

        assertTrue(controller.canName)
        controller.forkConversation(source, name = "Side quest")

        assertEquals(listOf(ConversationForkRequest("conv-1", "agent-1", null)), requests)
        assertEquals("Side quest", opened.single().conversation.summary)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        branching: ConversationBranching,
        lineage: ConversationLineage? = null,
        online: Boolean = true,
    ) = ConversationBranchController(
        scope = this,
        branching = { branching },
        opener = { opened += it },
        lineage = lineage,
        isOnline = { online },
        onError = { errors += it },
    )

    private fun branching(): ConversationBranching {
        var count = 0
        return ConversationBranching(BranchBackend(fork = { conversation("fork-${++count}") }, createEmpty = { conversation("empty") }))
    }

    internal class MapStore : SecureSettingsStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun clear() = values.clear()
    }
}
