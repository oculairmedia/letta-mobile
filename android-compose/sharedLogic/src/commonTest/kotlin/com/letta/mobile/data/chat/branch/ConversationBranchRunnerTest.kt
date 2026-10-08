package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.conversation
import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.prompt
import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ConversationBranchRunnerTest {
    private val timeline = listOf(prompt("m1", "hello"), reply("m2", "hi"), prompt("m3", "bye"))
    private val source = BranchSource("conv-1", "agent-1", timeline)

    @Test
    fun anEditOpensTheForkWithThePromptAsItsDraft() = runTest(UnconfinedTestDispatcher()) {
        val opened = mutableListOf<Pair<String, String?>>()
        val branching = ConversationBranching(BranchBackend(fork = { conversation("fork-1") }, createEmpty = { conversation("empty") }))
        val errors = mutableListOf<String>()
        ConversationBranchRunner(this) { message -> errors.add(message) }
            .editAndResend(branching, source, timeline[2], BranchOpener { _, fork, draft -> opened.add(fork.id.value to draft) })

        assertEquals(listOf<Pair<String, String?>>("fork-1" to "bye"), opened)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun aFailureIsReportedAndTheNextBranchRuns() = runTest(UnconfinedTestDispatcher()) {
        val errors = mutableListOf<String>()
        var fail = true
        val branching = ConversationBranching(
            BranchBackend(
                fork = { if (fail) error("host offline") else conversation("fork-2") },
                createEmpty = { conversation("empty") },
            ),
        )
        val runner = ConversationBranchRunner(this) { message -> errors.add(message) }
        val opened = mutableListOf<String>()

        runner.forkFrom(branching, source, timeline[0], BranchOpener { _, fork, _ -> opened.add(fork.id.value) })
        fail = false
        runner.forkFrom(branching, source, timeline[0], BranchOpener { _, fork, _ -> opened.add(fork.id.value) })

        assertEquals(listOf("host offline"), errors)
        assertEquals(listOf("fork-2"), opened)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var forks = 0
        val branching = ConversationBranching(
            BranchBackend(fork = { forks++; gate.await(); conversation("fork") }, createEmpty = { conversation("empty") }),
        )
        val runner = ConversationBranchRunner(this) {}

        runner.forkFrom(branching, source, timeline[0], BranchOpener { _, _, _ -> })
        runner.forkFrom(branching, source, timeline[0], BranchOpener { _, _, _ -> })
        gate.complete(Unit)

        assertEquals(1, forks)
    }
}
