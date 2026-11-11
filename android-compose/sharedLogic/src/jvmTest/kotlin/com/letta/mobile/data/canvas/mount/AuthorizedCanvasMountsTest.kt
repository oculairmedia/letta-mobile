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

    @Test
    fun readMatrix() = runBlocking {
        for (seeded in listOf(false, true)) {
            val root = root(seeded)
            for (scope in answers) for (winner in answers) {
                val mounts = facade(root, TableAuthority(scope, Answer.DENY, winner))
                val expected = when {
                    scope != Answer.ALLOW -> CanvasMountResult.Denied
                    !seeded -> CanvasMountResult.None
                    winner != Answer.ALLOW -> CanvasMountResult.Denied
                    else -> CanvasMountResult.Found(existing)
                }
                val label = "seeded=$seeded scope=$scope winner=$winner"
                assertEquals(expected, mounts.read(binding()), label)
                assertEquals(expected, mounts.read(agentDefault()), label)
            }
        }
    }

    @Test
    fun bindMatrix() = runBlocking {
        for (seeded in listOf(false, true)) for (scope in answers) for (proposal in answers) for (winner in answers) {
            val root = root(seeded)
            val result = facade(root, TableAuthority(scope, proposal, winner)).bindIfAbsent(binding(), proposed)
            val authorized = scope == Answer.ALLOW && proposal == Answer.ALLOW
            val winnerId = if (seeded) existing else proposed
            val expected = if (authorized && winner == Answer.ALLOW) CanvasMountResult.Bound(winnerId) else CanvasMountResult.Denied
            val label = "seeded=$seeded scope=$scope proposal=$proposal winner=$winner"
            assertEquals(expected, result, label)
            // An existing record is never rewritten; an absent one holds the caller's proposal only once it was authorized.
            val storedAfter = if (seeded) existing else proposed.takeIf { authorized }
            assertEquals(storedAfter, raw(root).readBinding(agent, conversation), label)
        }
    }

    @Test
    fun replaceAndSetMatrix() = runBlocking {
        for (seeded in listOf(false, true)) for (scope in answers) for (proposal in answers) for (winner in answers) {
            val root = root(seeded)
            val mounts = facade(root, TableAuthority(scope, proposal, winner))
            val authorized = scope == Answer.ALLOW && proposal == Answer.ALLOW
            val label = "seeded=$seeded scope=$scope proposal=$proposal winner=$winner"
            val replaced = if (authorized) CanvasMountResult.Replaced(proposed) else CanvasMountResult.Denied
            val set = if (authorized) CanvasMountResult.DefaultSet(proposed) else CanvasMountResult.Denied
            assertEquals(replaced, mounts.replaceBinding(binding(), proposed), label)
            assertEquals(set, mounts.setDefault(agentDefault(), proposed), label)
            val storedAfter = if (authorized) proposed else existing.takeIf { seeded }
            assertEquals(storedAfter, raw(root).readBinding(agent, conversation), label)
            assertEquals(storedAfter, raw(root).readDefault(agent), label)
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
