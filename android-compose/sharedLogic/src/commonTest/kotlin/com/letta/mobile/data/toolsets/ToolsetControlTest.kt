@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.data.toolsets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ToolsetControlTest {
    private val runtime = ToolsetRuntime("agent-1", "conv-1")
    private val initial = ToolsetState(listOf(ToolsetOption("auto", "Auto"), ToolsetOption("codex", "Codex")), "auto")

    @Test
    fun listsBothHostChoicesAndWaitsForEchoAfterExactlyOneSend() = runTest {
        val statuses = MutableStateFlow(mapOf(runtime to initial))
        val sent = mutableListOf<String>()
        val accepted = CompletableDeferred<Unit>()
        val control = ToolsetControl(backgroundScope, runtime, statuses) { sent += it; accepted.await() }
        runCurrent()
        assertEquals(listOf("auto", "codex"), control.state.value.available.map { it.id })
        assertEquals("auto", control.state.value.preference)
        control.select("codex")
        control.select("codex")
        runCurrent()
        assertEquals(listOf("codex"), sent)
        assertEquals("auto", control.state.value.preference)
        accepted.complete(Unit)
        runCurrent()
        assertEquals("auto", control.state.value.preference, "RPC success is not a preference echo")
        assertTrue(control.state.value.changing)
        statuses.value += ToolsetRuntime("other", "conv-1") to initial.copy(preference = "codex")
        runCurrent()
        assertEquals("auto", control.state.value.preference, "another runtime cannot confirm this pick")
        statuses.value += runtime to initial.copy(preference = "codex")
        runCurrent()
        assertEquals("codex", control.state.value.preference)
        assertFalse(control.state.value.changing)
    }

    @Test
    fun failuresAndMissingEchoAreVisibleAndLeaveTheHostSelectionIntact() = runTest {
        val statuses = MutableStateFlow(mapOf(runtime to initial))
        var fail = true
        var calls = 0
        val control = ToolsetControl(backgroundScope, runtime, statuses) {
            calls++
            if (fail) error("host refused")
        }
        control.select("auto")
        control.select("unknown")
        runCurrent()
        assertEquals(0, calls)
        control.select("codex")
        runCurrent()
        assertEquals("host refused", control.state.value.error)
        assertEquals("auto", control.state.value.preference)
        fail = false
        control.select("codex")
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals("The host did not confirm the toolset change", control.state.value.error)
        assertEquals("auto", control.state.value.preference)
        assertFalse(control.state.value.changing)
        assertEquals(2, calls)
    }

    @Test
    fun synchronousEchoIsNotLostAndAutoStaysTheSelectedPreference() = runTest {
        val statuses = MutableStateFlow(mapOf(runtime to initial.copy(preference = "codex")))
        val control = ToolsetControl(backgroundScope, runtime, statuses) {
            statuses.value = mapOf(runtime to initial)
        }
        control.select("auto")
        runCurrent()
        assertEquals("auto", control.state.value.preference)
        assertFalse(control.state.value.changing)
        assertEquals(null, control.state.value.error)
    }
}
