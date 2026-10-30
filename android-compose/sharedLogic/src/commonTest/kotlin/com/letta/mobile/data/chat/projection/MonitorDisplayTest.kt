package com.letta.mobile.data.chat.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitorDisplayTest {
    @Test
    fun githubPayloadsRemainReadableWithoutMarkup() {
        val raw = """
            New PR comment from @github-actions: <!-- 0 failure: 1 warning: PR touches web/de... DangerID: danger-id-danger/kotlin; --> <table> <thead> <tr> <th width="50"></th> <th width="100%" data-danger-table="true">Warnings</th> </tr> </thead> <tbody><tr> <td>:warning:</td> <td>PR to… https://github.com/oculairmedia/letta-mobile/pull/2281#issuecomment-6515332548
            New commented review from @codescene-access: [//]: # (cs-code-health) **Gates Failed** [![](https://codescene.io/imgs/svg/x1.svg)](#) New code is healthy `(1 new file with code health below 10.00)` [![](https://codescene.io/imgs/svg/x1.svg)](#) Enforce advisory code health rules `(2 f… https://github.com/oculairmedia/letta-mobile/pull/2281#pullrequestreview-5708244648
            New inline review comment from @codescene-access on android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/ConversationAdminHandlers.kt:135: [//]: # (cs-code-health) ❌ New issue: [**Complex Method**](https://codescene.io/projects/82362/delta?repo-id=1664655&review-id=2281&biomarker=Complex+Method&filename=android-compose%2FsharedLogic%2Fsrc%2FjvmAndAndroid%2Fkotlin%2Fcom%2Fletta… https://github.com/oculairmedia/letta-mobile/pull/2281#discussion_r442057280...(truncated)
            Check failed: CodeScene Code Health Review (main) (FAILURE). https://codescene.io/projects/82362/delta/results/8200841
        """.trimIndent()
        val lines = monitorDisplayLines(raw)
        assertEquals(4, lines.size)
        assertClean(lines.joinToString("\n"))
        assertTrue(lines[0].contains("0 failure, 1 warning"))
        assertTrue(lines[1].contains("Gates Failed"))
        assertTrue(lines[2].contains("Complex Method"))
        assertTrue(lines[2].contains("ConversationAdminHandlers.kt:135"))
        assertFalse(lines[2].contains("android-compose/"))
        assertTrue(lines[3].contains("Check failed: CodeScene Code Health Review (main)"))
        assertFalse(isActionableMonitorEvent(raw))
        assertEquals(listOf(true, false, true, true), raw.lines().map(::isActionableMonitorEvent))
    }

    @Test
    fun otherWatcherEventsKeepMeaningAndClassification() {
        val fixtures = listOf(
            "PR head changed from 9fea204411af to 454ecd7ac7b5. 1/6 checks complete, 0 failing, 5 pending; merge state BLOCKED, 4 unresolved review threads.",
            "New PR comment from @sonarqubecloud: ## [![Quality Gate Passed](https://sonarsource.github.io/sonarcloud-github-static-resources/v2/checks/QualityGateBadge/qg-passed-20px.png 'Quality Gate Passed')](https://sonarcloud.io/dashboard?id=oculairmedia_letta-mobile&pullRequest=2282)… https://github.com/oculairmedia/letta-mobile/pull/2282#issuecomment-6515420405",
            "ALL REQUIRED TERMINAL: build-apk-pass=completed/success detekt=completed/success ...",
            "[Monitor timed out — re-arm if needed.]",
        )
        val actionable = listOf(false, false, false, true)
        fixtures.forEachIndexed { index, fixture ->
            val cleaned = monitorDisplayLines(fixture).single()
            assertClean(cleaned)
            assertEquals(actionable[index], isActionableMonitorEvent(fixture))
        }
        assertTrue(monitorDisplayLines(fixtures[1]).single().contains("Quality Gate Passed"))
        assertEquals(fixtures[0], monitorDisplayLines(fixtures[0]).single())
        assertEquals(fixtures[2], monitorDisplayLines(fixtures[2]).single())
        assertEquals(fixtures[3], monitorDisplayLines(fixtures[3]).single())
    }

    @Test
    fun truncatedCommentCannotHideLaterBatchItems() {
        val lines = monitorDisplayLines("New PR comment: <!-- 0 failure: 1 warning: truncated\nCheck failed: build")
        assertEquals(listOf("New PR comment: 0 failure, 1 warning", "Check failed: build"), lines)
    }

    @Test
    fun batchesAreBoundedAndBlankLinesDoNotCount() {
        assertEquals(listOf("1", "2", "3", "4", "5", "+2 more"), monitorDisplayLines("\n1\n2\n3\n4\n5\n6\n7\n"))
    }

    private fun assertClean(text: String) {
        listOf("<!--", "<table", "[//]:", "![](", "](", "**", "https://", "(truncated)").forEach {
            assertFalse(text.contains(it), "Unexpected $it in $text")
        }
    }
}
