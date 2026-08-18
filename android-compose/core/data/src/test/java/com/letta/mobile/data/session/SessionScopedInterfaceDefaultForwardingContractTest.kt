package com.letta.mobile.data.session

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract guard for the SessionScoped "interface default silently wins"
 * defect class (SOP §25: listConversationsForAgent on #1230, listAgentSummaries
 * here).
 *
 * When an I*Repository method gains a default body AND a concrete repository
 * overrides it for a real wire/capability path, Hilt's SessionScoped wrapper
 * MUST also override and forward — otherwise production takes the interface
 * default forever.
 *
 * This test does not parse Kotlin AST; it asserts the known load-bearing
 * forwards that already bit us. Expand the table when the next miss is found
 * (e.g. add listConversationsForAgent once #1230 lands on main) rather than
 * inventing a brittle full-interface scanner.
 */
class SessionScopedInterfaceDefaultForwardingContractTest {

    @Test
    fun `SessionScopedAgentRepository forwards listAgentSummaries`() {
        assertForwards(
            scopedFile = "SessionScopedAgentRepository.kt",
            method = "listAgentSummaries",
        )
    }

    private fun assertForwards(scopedFile: String, method: String) {
        val source = File("src/main/java/com/letta/mobile/data/session/$scopedFile").readText()
        val pattern = Regex("""override\s+suspend\s+fun\s+$method\s*\(""")
        assertTrue(
            "$scopedFile must override $method and forward to the current session " +
                "repository; leaving the interface default in place silently drops " +
                "the concrete wire/capability path (SOP §25).",
            pattern.containsMatchIn(source),
        )
        assertTrue(
            "$scopedFile.$method override must forward via withCurrentSession / " +
                "current-session repository, not reimplement the interface default.",
            source.contains("withCurrentSession") && source.contains("$method("),
        )
    }
}
