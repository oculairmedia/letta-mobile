package com.letta.mobile.data.presence

import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ActiveTurnContractTest {
    @Test
    fun fixtureDecodesRoundTripsAndArbitratesEveryBoundary() {
        val cases = LettaJson.wire.decodeFromString<List<TurnCase>>(activeTurnContractText)
        assertEquals(7, cases.size)
        cases.forEach { case ->
            var state = case.initial
            case.steps.forEachIndexed { index, step ->
                state = arbitrateActiveTurn(state, step.candidate, step.boundary)
                assertEquals(step.expected, state, "${case.name} step $index")
                assertEquals(state, LettaJson.persisted.decodeFromString<ActiveTurnState>(
                    LettaJson.persisted.encodeToString(state),
                ))
            }
        }
    }

    @Serializable
    private data class TurnCase(val name: String, val initial: ActiveTurnState, val steps: List<Step>)

    @Serializable
    private data class Step(
        val boundary: ActiveTurnBoundary,
        val candidate: ActiveTurnState,
        val expected: ActiveTurnState,
    )
}
