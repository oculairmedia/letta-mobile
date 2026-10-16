package com.letta.mobile.data.model

import com.letta.mobile.data.controller.AppServerApprovalDecisions
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.runtime.RuntimeUserInputTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** letta-mobile-8fq4j: reading the plan an approval proposes, and the answer that accepts it. */
class PlanApprovalTest {
    @Test
    fun exitPlanModeIsARuntimeUserInputTool() {
        // Mirrors letta-code's RUNTIME_USER_INPUT_TOOLS: never auto-approved, under any mode.
        assertTrue(RuntimeUserInputTools.requiresUserInput("ExitPlanMode"))
        assertTrue(PlanApproval.isExitPlanMode("ExitPlanMode"))
    }

    @Test
    fun exitPlanModeReadsItsPlanField() {
        val plan = PlanApproval.preview("ExitPlanMode", """{"plan":"## Plan\n1. Do it"}""")
        assertEquals(PlanPreview("## Plan\n1. Do it", filePath = null), plan)
    }

    @Test
    fun exitPlanModeReadsDoubleEncodedArguments() {
        val plan = PlanApproval.preview("ExitPlanMode", "\"{\\\"plan\\\":\\\"Ship\\\"}\"")
        assertEquals("Ship", plan?.content)
    }

    @Test
    fun exitPlanModeWithoutAPlanShowsItsRawArguments() {
        val plan = PlanApproval.preview("ExitPlanMode", """{"steps":["a"]}""")
        assertTrue(plan!!.content.contains("\"steps\""), plan.content)
    }

    @Test
    fun aWriteToAPlansFileIsAPlan() {
        val plan = PlanApproval.preview("Write", """{"file_path":"/home/me/.letta/plans/ship.md","content":"# Ship"}""")
        assertEquals(PlanPreview("# Ship", "/home/me/.letta/plans/ship.md"), plan)
        assertEquals(".../plans/ship.md", plan?.shortFilePath)
    }

    @Test
    fun aWindowsPlansPathIsAPlan() {
        val plan = PlanApproval.preview("Write", """{"file_path":"C:\\Users\\me\\.letta\\plans\\ship.md","content":"# Ship"}""")
        assertEquals(".../plans/ship.md", plan?.shortFilePath)
    }

    @Test
    fun aWriteElsewhereIsNoPlan() {
        assertNull(PlanApproval.preview("Write", """{"file_path":"/repo/README.md","content":"# Readme"}"""))
        assertNull(PlanApproval.preview("Write", """{"file_path":"/repo/plans/nested/x.md","content":"x"}"""))
        assertNull(PlanApproval.preview("Bash", """{"command":"ls"}"""))
        assertNull(PlanApproval.preview("ExitPlanMode", null))
    }

    @Test
    fun aPatchAddingAPlansFileIsAPlan() {
        val patch = "*** Begin Patch\n*** Add File: /home/me/.letta/plans/ship.md\n+# Ship\n+- step\n*** End Patch"
        val args = """{"input":${JsonPrimitive(patch)}}"""
        assertEquals(PlanPreview("# Ship\n- step", "/home/me/.letta/plans/ship.md"), PlanApproval.preview("ApplyPatch", args))
    }

    @Test
    fun approvingClosesTheCallWithItsOwnArguments() {
        val reason = PlanApproval.approveReason("""{"plan":"Ship"}""")
        val decision = AppServerApprovalDecisions.decide(
            approve = true,
            updatedInput = AskUserQuestion.decodeAnswerReason(reason),
            message = reason,
            defaultApproveMessage = "Approved.",
            defaultDenyMessage = "Denied.",
        )
        val allow = assertIs<AppServerApprovalResponseDecision.Allow>(decision)
        assertEquals("Ship", allow.updatedInput?.get("plan")?.jsonPrimitive?.content)
        assertNull(allow.message)
    }

    @Test
    fun unreadableArgumentsApproveWithoutAnUpdatedInput() {
        assertNull(PlanApproval.approveReason("not json"))
        assertNull(PlanApproval.approveReason(null))
    }
}
