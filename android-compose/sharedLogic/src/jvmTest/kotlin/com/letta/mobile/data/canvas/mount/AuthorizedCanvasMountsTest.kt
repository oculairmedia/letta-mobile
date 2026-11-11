package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The facade over the real file store: authorization matrix, isolation, and what a denial reveals. */
class AuthorizedCanvasMountsTest {
    private val host = HostId("host-one")
    private val agent = AgentId("agent-one")
    private val conversation = ConversationId("conversation-one")
    private val existing = CanvasId("secret-existing-board")
    private val proposed = CanvasId("proposed-board")
    private val answers = Answer.entries

    private suspend fun root(seeded: Boolean): Path {
        val root = Files.createTempDirectory("authorized-mounts")
        if (seeded) {
            raw(root).bindIfAbsent(agent, conversation, existing)
            raw(root).setDefault(agent, existing)
        }
        return root
    }

    private fun raw(root: Path) = FileCanvasMountStore(root, host)

    private fun facade(root: Path, authority: CanvasMountAuthority) = AuthorizedCanvasMounts(root, host, authority)

    private fun binding() = bindingRequest(agent.value, conversation.value)

    private fun agentDefault() = defaultRequest(agent.value)

    /** One row of an authorization matrix: whether a record exists, and the authority's three answers. */
    private data class MatrixCase(val seeded: Boolean, val scope: Answer, val proposal: Answer, val winner: Answer) {
        val scopeAllowed: Boolean get() = scope == Answer.ALLOW
        val authorized: Boolean get() = scopeAllowed && proposal == Answer.ALLOW
        val winnerAllowed: Boolean get() = winner == Answer.ALLOW
    }

    /** Every combination of record presence and the given answers. */
    private fun matrix(proposals: List<Answer> = answers): List<MatrixCase> = listOf(false, true).flatMap { seeded ->
        answers.flatMap { scope -> proposals.flatMap { proposal -> answers.map { MatrixCase(seeded, scope, proposal, it) } } }
    }

    private fun facadeFor(case: MatrixCase, root: Path) = facade(root, TableAuthority(case.scope, case.proposal, case.winner))

    private fun expectedRead(case: MatrixCase): CanvasMountResult = when {
        !case.scopeAllowed -> CanvasMountResult.Denied
        !case.seeded -> CanvasMountResult.None
        !case.winnerAllowed -> CanvasMountResult.Denied
        else -> CanvasMountResult.Found(existing)
    }

    private fun expectedBind(case: MatrixCase): CanvasMountResult {
        val seen = case.authorized && case.winnerAllowed
        return if (seen) CanvasMountResult.Bound(if (case.seeded) existing else proposed) else CanvasMountResult.Denied
    }

    /** An existing record is never rewritten; an absent one holds the caller's proposal only once it was authorized. */
    private fun storedAfterBind(case: MatrixCase): CanvasId? = if (case.seeded) existing else proposed.takeIf { case.authorized }

    private fun storedAfterReplace(case: MatrixCase): CanvasId? = if (case.authorized) proposed else existing.takeIf { case.seeded }

    @Test
    fun readMatrix() = runBlocking {
        for (case in matrix(proposals = listOf(Answer.DENY))) {
            val mounts = facadeFor(case, root(case.seeded))
            assertEquals(expectedRead(case), mounts.read(binding()), "$case")
            assertEquals(expectedRead(case), mounts.read(agentDefault()), "$case")
        }
    }

    @Test
    fun bindMatrix() = runBlocking {
        for (case in matrix()) {
            val root = root(case.seeded)
            assertEquals(expectedBind(case), facadeFor(case, root).bindIfAbsent(binding(), proposed), "$case")
            assertEquals(storedAfterBind(case), raw(root).readBinding(agent, conversation), "$case")
        }
    }

    @Test
    fun replaceAndSetMatrix() = runBlocking {
        for (case in matrix()) {
            val root = root(case.seeded)
            val mounts = facadeFor(case, root)
            val replaced = if (case.authorized) CanvasMountResult.Replaced(proposed) else CanvasMountResult.Denied
            val set = if (case.authorized) CanvasMountResult.DefaultSet(proposed) else CanvasMountResult.Denied
            assertEquals(replaced, mounts.replaceBinding(binding(), proposed), "$case")
            assertEquals(set, mounts.setDefault(agentDefault(), proposed), "$case")
            assertEquals(storedAfterReplace(case), raw(root).readBinding(agent, conversation), "$case")
            assertEquals(storedAfterReplace(case), raw(root).readDefault(agent), "$case")
        }
    }

    @Test
    fun deniedCallersGetIdenticalResultsWhetherOrNotARecordExists() = runBlocking {
        val denials = listOf(TableAuthority(Answer.DENY), TableAuthority(Answer.ABSTAIN), TableAuthority(Answer.ALLOW, Answer.DENY))
        for (authority in denials) {
            val outcomes = listOf(false, true).map { seeded ->
                val mounts = facade(root(seeded), authority)
                listOf(
                    mounts.bindIfAbsent(binding(), proposed),
                    mounts.replaceBinding(binding(), proposed),
                    mounts.setDefault(agentDefault(), proposed),
                ).map { it to it.toString() }
            }
            assertEquals(outcomes[0], outcomes[1])
            outcomes.flatten().forEach { assertEquals(CanvasMountResult.Denied to "Denied", it) }
        }
        val reads = listOf(false, true).map { seeded -> facade(root(seeded), TableAuthority(Answer.DENY)).read(binding()) }
        assertEquals(listOf(CanvasMountResult.Denied, CanvasMountResult.Denied), reads)
    }

    @Test
    fun deniedScopeCreatesNothingOnDisk() = runBlocking {
        val root = root(seeded = false)
        for (authority in listOf(TableAuthority(Answer.DENY), TableAuthority(Answer.ALLOW, Answer.DENY))) {
            val mounts = facade(root, authority)
            mounts.read(binding())
            mounts.bindIfAbsent(binding(), proposed)
            mounts.replaceBinding(binding(), proposed)
            assertEquals(CanvasMountResult.Denied, mounts.setDefault(agentDefault(), proposed))
        }
        assertEquals(emptyList<Path>(), Files.list(root).use { it.toList() }, "Denied calls and absent reads create nothing")
    }

    @Test
    fun crossAgentAndCrossConversationCallersAreDeniedAndCannotBindAVictimsBoard() = runBlocking {
        val root = root(seeded = false)
        val authority = OwnershipAuthority(
            "victim" to Grant("victim-agent", "victim-thread", "canvas-conversation-victim"),
            "attacker" to Grant("attacker-agent", "attacker-thread", "attacker-board"),
        )
        val mounts = facade(root, authority)
        val victimBoard = CanvasId("canvas-conversation-victim")
        assertEquals(
            CanvasMountResult.Bound(victimBoard),
            mounts.bindIfAbsent(bindingRequest("victim-agent", "victim-thread", "victim"), victimBoard),
        )
        val foreignScopes = listOf(
            bindingRequest("victim-agent", "victim-thread", "attacker"),
            bindingRequest("attacker-agent", "victim-thread", "attacker"),
            bindingRequest("victim-agent", "attacker-thread", "attacker"),
        )
        for (request in foreignScopes) {
            assertEquals(CanvasMountResult.Denied, mounts.read(request))
            assertEquals(CanvasMountResult.Denied, mounts.bindIfAbsent(request, CanvasId("attacker-board")))
            assertEquals(CanvasMountResult.Denied, mounts.replaceBinding(request, CanvasId("attacker-board")))
        }
        assertEquals(CanvasMountResult.Denied, mounts.read(defaultRequest("victim-agent", "attacker")))
        assertEquals(CanvasMountResult.Denied, mounts.setDefault(defaultRequest("victim-agent", "attacker"), CanvasId("attacker-board")))
        // Confused deputy: the attacker's own scope, proposing the victim's board.
        val own = bindingRequest("attacker-agent", "attacker-thread", "attacker")
        assertEquals(CanvasMountResult.Denied, mounts.bindIfAbsent(own, victimBoard))
        assertNull(raw(root).readBinding(AgentId("attacker-agent"), ConversationId("attacker-thread")))
        assertEquals(victimBoard, raw(root).readBinding(AgentId("victim-agent"), ConversationId("victim-thread")))
        assertNull(raw(root).readDefault(AgentId("victim-agent")))
    }

    @Test
    fun aDeniedWinnerAppearsInNoResultAndAnAbsentRecordKeepsTheCallersOwnProposal() = runBlocking {
        val hidden = TableAuthority(Answer.ALLOW, Answer.ALLOW, Answer.DENY)
        val seeded = root(seeded = true)
        val results = listOf(
            facade(seeded, hidden).read(binding()),
            facade(seeded, hidden).read(agentDefault()),
            facade(seeded, hidden).bindIfAbsent(binding(), proposed),
        )
        results.forEach { result ->
            assertEquals(CanvasMountResult.Denied, result)
            assertFalse(result.toString().contains(existing.value), result.toString())
        }
        assertEquals(existing, raw(seeded).readBinding(agent, conversation), "An existing winner is never rewritten")
        val empty = root(seeded = false)
        assertEquals(CanvasMountResult.Denied, facade(empty, hidden).bindIfAbsent(binding(), proposed))
        assertEquals(proposed, raw(empty).readBinding(agent, conversation), "The caller's authorized proposal won the empty record")
    }

    @Test
    fun aCorruptRecordIsAStorageFailureWithoutPathOrContents() = runBlocking {
        val root = root(seeded = true)
        val record = Files.list(root.resolve("canvas-mounts")).use { paths ->
            paths.filter { it.fileName.toString().startsWith("binding-") }.toList().single()
        }
        Files.writeString(record, "secret-corrupt-contents")
        val mounts = facade(root, TableAuthority(Answer.ALLOW))
        val results = listOf(
            mounts.read(binding()),
            mounts.bindIfAbsent(binding(), proposed),
            mounts.replaceBinding(binding(), proposed),
        )
        results.forEach { result ->
            assertEquals(CanvasMountResult.StorageFailure(CanvasMountFailure.INVALID_STATE), result)
            listOf(root.toString(), record.fileName.toString(), "canvas-mounts", "secret").forEach { leaked ->
                assertFalse(result.toString().contains(leaked), result.toString())
            }
        }
        assertEquals("secret-corrupt-contents", Files.readString(record))
        assertEquals(CanvasMountResult.Denied, facade(root, TableAuthority(Answer.DENY)).read(binding()))
    }
}

/** One principal's scope and the only canvas it may propose or see. */
private class Grant(val agent: String, val conversation: String, val canvas: String)

/** A realistic authority: each principal owns one agent, one conversation and one board. */
private class OwnershipAuthority(vararg grants: Pair<String, Grant>) : CanvasMountAuthority {
    private val grants = grants.toMap()

    override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision {
        val grant = grants[request.principal.endpoint] ?: return CanvasMountDecision.ABSTAIN
        val scope = request.scope
        val conversationOk = scope !is CanvasMountScope.Conversation || scope.conversation.value == grant.conversation
        return decision(scope.agent.value == grant.agent && conversationOk)
    }

    override suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId) = owns(request, proposed)

    override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId) = owns(request, winner)

    private fun owns(request: CanvasMountRequest<CanvasMountScope>, canvas: CanvasId): CanvasMountDecision =
        decision(grants[request.principal.endpoint]?.canvas == canvas.value)

    private fun decision(allowed: Boolean) = if (allowed) CanvasMountDecision.ALLOW else CanvasMountDecision.DENY
}
