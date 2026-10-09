package com.letta.mobile.data.runtime

import com.letta.mobile.data.chat.projection.requiresUserInput
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.controller.AppServerApprovalDecisions
import com.letta.mobile.data.controller.withSelectedSuggestions
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.PermissionSuggestion
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** letta-mobile-bzvro.11 / .12: the optional parts of a 0.33.6 `can_use_tool` control request. */
class ApprovalRequestDetailsTest {
    private val mapper = AppServerRuntimeEventMapper()

    // A serialized 0.33.6 Edit approval: a rule, a blocked path and a structuredPatch-style diff,
    // plus fields this client does not know.
    private val editRequest = """
        {"subtype":"can_use_tool","tool_name":"Edit","tool_call_id":"call_edit_1",
         "input":{"file_path":"/repo/a.txt","old_string":"one","new_string":"two"},
         "permission_suggestions":[
           {"id":"allow-edit-repo","text":"Edit(/repo/**)","future_field":true},
           {"id":"","text":"dropped, blank id"},
           {"text":"dropped, no id"},
           "not an object"],
         "blocked_path":"/repo/a.txt",
         "diffs":[
           {"fileName":"/repo/a.txt","hunks":[
              {"oldStart":3,"oldLines":2,"newStart":3,"newLines":2,"lines":[" keep","-one","+two"]}],
            "mode":"advanced"},
           {"file_path":"/repo/big.bin","reason":"binary file"}],
         "future_top_level":{"a":1}}
    """.trimIndent()

    @Test
    fun mapsSuggestionsBlockedPathAndDiffs() {
        val request = approvalFor(editRequest)

        assertEquals(listOf(PermissionSuggestion("allow-edit-repo", "Edit(/repo/**)")), request.suggestions)
        assertEquals("/repo/a.txt", request.blockedPath)
        assertEquals(2, request.diffs.size)
        assertEquals(
            ApprovalDiffPreview(
                path = "/repo/a.txt",
                unifiedDiff = "@@ -3,2 +3,2 @@\n keep\n-one\n+two",
            ),
            request.diffs[0],
        )
        assertEquals(ApprovalDiffPreview(path = "/repo/big.bin", unifiedDiff = null, note = "binary file"), request.diffs[1])
        assertEquals("""{"file_path":"/repo/a.txt","old_string":"one","new_string":"two"}""", request.argumentsPreview)
    }

    @Test
    fun readsAUnifiedDiffStringVerbatim() {
        val request = approvalFor(
            """{"subtype":"can_use_tool","tool_name":"Write","tool_call_id":"c1",
               "diffs":[{"path":"x.kt","unified_diff":"@@ -1 +1 @@\n-a\n+b"}]}""",
        )
        assertEquals("@@ -1 +1 @@\n-a\n+b", request.diffs.single().unifiedDiff)
    }

    @Test
    fun aHostileHugeDiffIsBoundedAtParseTime() {
        val huge = "+x\\n".repeat(1_000_000)
        val many = (1..50).joinToString(",") { """{"path":"f$it","unified_diff":"@@ -1 +1 @@\\n-a\\n+b"}""" }
        val big = approvalFor("""{"subtype":"can_use_tool","tool_name":"Write","tool_call_id":"c1","diffs":[{"path":"${"p".repeat(50_000)}","unified_diff":"$huge"}]}""")
        val diff = big.diffs.single()
        val text = diff.unifiedDiff!!
        assertTrue(text.length <= ApprovalRequestPayloadParser.MAX_DIFF_CHARS + 64, "chars bounded: ${text.length}")
        assertTrue(text.lines().size <= ApprovalRequestPayloadParser.MAX_DIFF_LINES + 1)
        assertTrue(text.endsWith(ApprovalRequestPayloadParser.TRUNCATION_MARKER))
        assertEquals(ApprovalRequestPayloadParser.MAX_LABEL_CHARS, diff.path!!.length)

        val capped = approvalFor("""{"subtype":"can_use_tool","tool_name":"Write","tool_call_id":"c2","diffs":[$many]}""")
        assertEquals(ApprovalRequestPayloadParser.MAX_DIFFS, capped.diffs.size)
    }

    @Test
    fun aRequestWithoutTheOptionalFieldsKeepsTheOldShape() {
        val request = approvalFor(
            """{"subtype":"can_use_tool","tool_name":"Bash","tool_call_id":"c1","input":{"command":"ls"},
               "permission_suggestions":[],"blocked_path":null}""",
        )
        assertEquals(emptyList(), request.suggestions)
        assertNull(request.blockedPath)
        assertEquals(emptyList(), request.diffs)
    }

    @Test
    fun malformedOptionalFieldsDegradeToAbsentInsteadOfThrowing() {
        val request = approvalFor(
            """{"subtype":"can_use_tool","tool_name":"Bash","tool_call_id":"c1",
               "permission_suggestions":"nope","blocked_path":{"a":1},"diffs":{"x":1}}""",
        )
        assertEquals(emptyList(), request.suggestions)
        assertNull(request.blockedPath)
        assertEquals(emptyList(), request.diffs)
    }

    @Test
    fun theRequestRoundTripsAndOldPayloadsDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val request = approvalFor(editRequest)
        assertEquals(request, json.decodeFromString(ToolApprovalRequest.serializer(), json.encodeToString(ToolApprovalRequest.serializer(), request)))

        val legacy = json.decodeFromString(
            ToolApprovalRequest.serializer(),
            """{"approvalId":"a","callId":"c","toolName":"Bash","prompt":"Allow Bash?"}""",
        )
        assertEquals(emptyList(), legacy.suggestions)
        assertEquals(emptyList(), legacy.diffs)
    }

    @Test
    fun choosingARuleSendsItsIdOnTheAllowDecision() {
        val decision = AppServerApprovalDecisions.decide(true, null, null, "ok", "no")
            .withSelectedSuggestions(listOf("allow-edit-repo"))

        val wire = Json.parseToJsonElement(Json.encodeToString(AppServerApprovalResponseDecision.serializer(), decision)).jsonObject
        assertEquals("allow", (wire["behavior"] as JsonPrimitive).content)
        assertEquals(listOf("allow-edit-repo"), (wire["selected_permission_suggestion_ids"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test
    fun aPlainApprovalOrDenialCarriesNoRuleIds() {
        val allow = AppServerApprovalDecisions.decide(true, null, null, "ok", "no").withSelectedSuggestions(emptyList())
        assertNull(assertIs<AppServerApprovalResponseDecision.Allow>(allow).selectedPermissionSuggestionIds)
        val deny = AppServerApprovalDecisions.decide(false, null, null, "ok", "no").withSelectedSuggestions(listOf("x"))
        assertIs<AppServerApprovalResponseDecision.Deny>(deny)
    }

    @Test
    fun storeTracksAParkedRequestUntilItResolvesOrTheTurnEnds() {
        val store = PendingApprovalDetailsStore()
        val keyA = TurnRuntimeKey("agent", "conv-a")
        val keyB = TurnRuntimeKey("agent", "conv-b")
        store.record(keyA, details("call-a"))
        store.record(keyB, details("call-b"))
        assertEquals(setOf("call-a", "call-b"), store.pending.value.keys)

        store.resolve("call-a")
        assertEquals(setOf("call-b"), store.pending.value.keys)

        store.clearKey(keyA)
        assertEquals(setOf("call-b"), store.pending.value.keys, "another runtime's request survives")
        store.clearKey(keyB)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun aRequestWithParkedDetailsWaitsOnThePersonWhateverTheTool() {
        val bash = approval("call-1", "Bash")
        assertFalse(bash.requiresUserInput(), "approve-all resolves Bash itself: still the plain tool row")
        assertTrue(bash.copy(details = details("call-1")).requiresUserInput())
        assertTrue(approval("call-2", "AskUserQuestion").requiresUserInput())
    }

    @Test
    fun joinsParkedDetailsToTheMatchingApprovalByToolCallId() {
        val matching = message("m1", approval("call-1", "Edit"))
        val other = message("m2", approval("call-9", "Edit"))
        val plain = message("m3", null)
        val parked = mapOf("call-1" to details("call-1"))

        val joined = withPendingApprovalDetails(listOf(matching, other, plain), parked)

        assertEquals(parked["call-1"], joined[0].approvalRequest?.details)
        assertNull(joined[1].approvalRequest?.details)
        assertSame(plain, joined[2])
    }

    @Test
    fun joinReturnsTheSameListWhenNothingIsParked() {
        val messages = listOf(message("m1", approval("call-1", "Edit")))
        assertSame(messages, withPendingApprovalDetails(messages, emptyMap()))
    }

    @Test
    fun anAlwaysAllowIsHonouredOnlyForTheRequestItsCardOffered() {
        val a = details("call-a")
        val b = details("call-b")
        // Parallel calls [A, B]: A's card was drawn, A resolved, the card redraws for B.
        assertEquals("perm-call-b", b.approvalIdForSuggestions("call-b", "perm-call-b", listOf("s1")))
        // A rule offered on A's card must never be attached to B (nor to a missing request).
        assertNull(b.approvalIdForSuggestions("call-a", "perm-call-a", listOf("s1")))
        assertNull(null.approvalIdForSuggestions("call-a", "perm-call-a", listOf("s1")))
        // A re-surfaced request (new approval id) or a suggestion no longer offered is refused.
        assertNull(a.copy(approvalId = "perm-new").approvalIdForSuggestions("call-a", "perm-call-a", listOf("s1")))
        assertNull(a.approvalIdForSuggestions("call-a", "perm-call-a", listOf("gone")))
        assertNull(a.approvalIdForSuggestions(null, null, listOf("s1")))
    }

    @Test
    fun resolvingByApprovalIdKeepsANewerRequestForTheSameCall() {
        val store = PendingApprovalDetailsStore()
        val key = TurnRuntimeKey("agent", "conv-a")
        store.record(key, details("call-a").copy(approvalId = "perm-new"))

        store.resolveIfApproval("call-a", "perm-old")
        assertEquals(setOf("call-a"), store.pending.value.keys)
        store.resolveIfApproval("call-a", "perm-new")
        assertTrue(store.pending.value.isEmpty())
    }

    private fun details(toolCallId: String) = PendingApprovalDetails(
        approvalId = "perm-$toolCallId",
        toolCallId = toolCallId,
        toolName = "Edit",
        suggestions = listOf(PermissionSuggestion("s1", "Edit(**)")),
    )

    private fun approval(toolCallId: String, tool: String) = UiApprovalRequest(
        requestId = "req-$toolCallId",
        toolCalls = listOf(UiApprovalToolCall(toolCallId = toolCallId, name = tool, arguments = "{}")),
    )

    private fun message(id: String, approval: UiApprovalRequest?) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-10-09T00:00:00Z",
        approvalRequest = approval,
    )

    private fun approvalFor(requestJson: String): ToolApprovalRequest {
        val request = Json.parseToJsonElement(requestJson).jsonObject
        val drafts = mapper.map(command, received(AppServerInboundFrame.ControlRequest("perm-1", request, "agent-1", "conv-1")))
        return assertIs<RuntimeEventPayload.ApprovalRequested>(drafts.single().payload).request
    }

    private fun received(frame: AppServerInboundFrame) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = frame,
        raw = JsonObject(emptyMap()),
    )

    private companion object {
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )
    }
}
