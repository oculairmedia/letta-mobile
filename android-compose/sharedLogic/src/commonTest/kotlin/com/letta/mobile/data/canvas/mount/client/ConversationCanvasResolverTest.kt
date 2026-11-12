package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Which board a conversation shows: the host's mount when it offers one, else the conversation's own. */
class ConversationCanvasResolverTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val foreign = CanvasId("canvas-foreign")

    private class Case(
        val name: String,
        val supported: Boolean,
        val answers: List<CanvasMountLookup>,
        val expected: ConversationCanvasState,
        val asks: Int,
    )

    private val unavailable = CanvasMountProblem.Unavailable(CanvasMountUnavailableReason("io_failure"))
    private val unknown = CanvasMountLookup.Failed(CanvasMountProblem.OutcomeUnknown)

    private val cases = listOf(
        Case("unsupported host is never asked", supported = false, answers = emptyList(), expected = ConversationCanvasState.Own, asks = 0),
        Case(
            "nothing mounted", supported = true, answers = listOf(CanvasMountLookup.None),
            expected = ConversationCanvasState.Own, asks = 1,
        ),
        Case(
            "a binding mounts another board", supported = true,
            answers = listOf(CanvasMountLookup.Found(foreign, CanvasMountSource.Binding)),
            expected = ConversationCanvasState.Mounted(foreign, CanvasMountSource.Binding), asks = 1,
        ),
        Case(
            "the agent default mounts another board", supported = true,
            answers = listOf(CanvasMountLookup.Found(foreign, CanvasMountSource.Default)),
            expected = ConversationCanvasState.Mounted(foreign, CanvasMountSource.Default), asks = 1,
        ),
        Case(
            "its own board mounted is no mount", supported = true,
            answers = listOf(CanvasMountLookup.Found(CanvasId.forConversation(ConversationId("conv-1")), CanvasMountSource.Binding)),
            expected = ConversationCanvasState.Own, asks = 1,
        ),
        Case(
            "denied", supported = true, answers = listOf(CanvasMountLookup.Failed(CanvasMountProblem.Denied)),
            expected = ConversationCanvasState.Unavailable(CanvasMountProblem.Denied), asks = 1,
        ),
        Case(
            "unavailable", supported = true, answers = listOf(CanvasMountLookup.Failed(unavailable)),
            expected = ConversationCanvasState.Unavailable(unavailable), asks = 1,
        ),
        Case(
            "outcome unknown is asked again", supported = true,
            answers = listOf(unknown, CanvasMountLookup.Found(foreign, CanvasMountSource.Binding)),
            expected = ConversationCanvasState.Mounted(foreign, CanvasMountSource.Binding), asks = 2,
        ),
        Case(
            "outcome unknown that stays unknown is unavailable", supported = true, answers = List(3) { unknown },
            expected = ConversationCanvasState.Unavailable(
                CanvasMountProblem.Unavailable(CanvasMountUnavailableReason.StillUnknown),
            ),
            asks = 3,
        ),
    )

    @Test
    fun resolvesEachHostAnswer() = runTest {
        for (case in cases) {
            val client = FakeCanvasMountClient(case.supported).apply { answers.addAll(case.answers) }
            val resolver = ConversationCanvasResolver(client)
            assertEquals(case.expected, resolver.refresh(target), case.name)
            assertEquals(case.expected, resolver.current(target), case.name)
            assertEquals(case.asks, client.resolves, case.name)
        }
    }

    @Test
    fun opensOnTheOwnBoardWhileTheHostIsAsked() = runTest {
        val client = FakeCanvasMountClient().apply { stored[target] = foreign }
        val states = ConversationCanvasResolver(client).state(target).take(2).toList()
        assertEquals(ConversationCanvasState.Resolving, states.first())
        assertEquals(ConversationCanvasState.Mounted(foreign, CanvasMountSource.Binding), states.last())
    }

    @Test
    fun aMountSwitchesTheConversationAndItAloneAndUnmountReturnsIt() = runTest {
        val client = FakeCanvasMountClient()
        val resolver = ConversationCanvasResolver(client)
        val other = target.copy(conversationId = ConversationId("conv-2"))
        assertIs<CanvasMountChange.Applied>(resolver.mount(target, foreign))
        assertEquals(foreign, resolver.current(target).mountedCanvas)
        assertEquals(ConversationCanvasState.Own, resolver.refresh(other))
        assertIs<CanvasMountChange.Applied>(resolver.unmount(target))
        assertEquals(ConversationCanvasState.Own, resolver.current(target))
    }

    private class WriteCase(val name: String, val reply: CanvasMountChange, val landed: Boolean, val expectApplied: Boolean)

    @Test
    fun writesSettleOnWhatTheHostHolds() = runTest {
        val denied = CanvasMountChange.Failed(CanvasMountProblem.Denied)
        val unknownWrite = CanvasMountChange.Failed(CanvasMountProblem.OutcomeUnknown)
        val writeCases = listOf(
            WriteCase("denied changes nothing", denied, landed = false, expectApplied = false),
            WriteCase("unknown that landed is applied", unknownWrite, landed = true, expectApplied = true),
            WriteCase("unknown that did not land stays unknown", unknownWrite, landed = false, expectApplied = false),
        )
        for (case in writeCases) {
            val client = FakeCanvasMountClient().apply {
                writeAnswers.add(case.reply)
                applyScripted = case.landed
            }
            val resolver = ConversationCanvasResolver(client)
            val change = resolver.mount(target, foreign)
            assertEquals(case.expectApplied, change is CanvasMountChange.Applied, case.name)
            assertEquals(case.landed, resolver.current(target).mountedCanvas == foreign, case.name)
        }
    }

    @Test
    fun aFailedConfirmationKeepsTheWriteThatWasApplied() = runTest {
        val client = FakeCanvasMountClient()
        val resolver = ConversationCanvasResolver(client)
        client.answers.add(CanvasMountLookup.Failed(unavailable))
        assertIs<CanvasMountChange.Applied>(resolver.mount(target, foreign))
        assertEquals(foreign, resolver.current(target).mountedCanvas)
    }

    @Test
    fun anUnsupportedHostIsNeverWrittenTo() = runTest {
        val client = FakeCanvasMountClient(supported = false)
        val change = ConversationCanvasResolver(client).mount(target, foreign)
        val problem = assertIs<CanvasMountChange.Failed>(change).problem
        assertEquals(CanvasMountProblem.Unavailable(CanvasMountUnavailableReason.Unsupported), problem)
        assertEquals(0, client.writes)
    }

    @Test
    fun theUnsupportedClientNeverChangesAnything() = runTest {
        val resolver = ConversationCanvasResolver(CanvasMountClient.Unsupported)
        assertEquals(ConversationCanvasState.Own, resolver.state(target).first { it != ConversationCanvasState.Resolving })
        assertIs<CanvasMountChange.Failed>(resolver.mount(target, foreign))
        assertEquals(ConversationCanvasState.Own, resolver.current(target))
    }

    @Test
    fun aPushedChangeIsTaken() = runTest {
        val resolver = ConversationCanvasResolver(FakeCanvasMountClient())
        resolver.accept(target, foreign)
        assertEquals(foreign, resolver.current(target).mountedCanvas)
    }
}
