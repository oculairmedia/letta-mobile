package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.14: the ratchet on the gate's waivers. Every waived check must still fail for
 * its fixture; once the bead's fix lands this test fails, and the waiver comes out of
 * [BridgeParityFixtures] so the gate guards the fix from then on.
 */
class IrohBridgeParityKnownDivergenceTest {

    @Test
    fun everyWaivedCheckStillDiverges() = runTest {
        val fixed = BridgeParityFixtures.ALL.filter { it.divergences.isNotEmpty() }.flatMap { fixture ->
            val runs = parityRuns(fixture)
            fixture.divergences.filterKeys { check -> check.verify(runs) == null }
                .map { (check, bead) -> "$fixture: $check passes now ($bead) - remove the waiver" }
        }
        assertTrue(fixed.isEmpty(), fixed.joinToString("\n"))
    }

    @Test
    fun everyWaiverNamesABead() {
        val unnamed = BridgeParityFixtures.ALL.flatMap { fixture ->
            fixture.divergences.filterValues { !it.startsWith("letta-mobile-") }.keys.map { "$fixture: $it" }
        }
        assertTrue(unnamed.isEmpty(), "waivers without a bead: $unnamed")
    }
}
