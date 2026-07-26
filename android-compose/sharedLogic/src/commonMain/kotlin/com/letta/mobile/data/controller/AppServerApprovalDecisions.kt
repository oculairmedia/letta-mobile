package com.letta.mobile.data.controller

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import kotlinx.serialization.json.JsonObject

/**
 * Pure request-shaping helper for App Server approval responses (AGENTS.md
 * shared request-shaping rule).
 *
 * The mobile Iroh path ([DefaultAppServerController.submitApproval]) and the
 * desktop gateway
 * ([com.letta.mobile.desktop.chat.DesktopHybridAppServerChatGateway.submitApproval])
 * both turn a (approve, updatedInput, message) triple into an
 * [AppServerApprovalResponseDecision]. Extracting the selection here keeps that
 * shaping in one place and drops both call sites below CodeScene's complexity
 * threshold. Callers still own the effectiveRequestId resolution (they hold the
 * turn engine); this helper only shapes the decision.
 *
 * @param approve whether the tool call was approved.
 * @param updatedInput an AskUserQuestion answer (or other structured close
 *   payload) that closes the call via `Allow(updated_input=…)`. Ignored when
 *   [approve] is false.
 * @param message the human-readable reason to attach to a plain allow/deny.
 */
object AppServerApprovalDecisions {
    fun decide(
        approve: Boolean,
        updatedInput: JsonObject?,
        message: String?,
        defaultApproveMessage: String,
        defaultDenyMessage: String,
    ): AppServerApprovalResponseDecision = when {
        approve && updatedInput != null ->
            AppServerApprovalResponseDecision.Allow(message = null, updatedInput = updatedInput)
        approve ->
            AppServerApprovalResponseDecision.Allow(message = message ?: defaultApproveMessage)
        else ->
            AppServerApprovalResponseDecision.Deny(message = message ?: defaultDenyMessage)
    }
}
