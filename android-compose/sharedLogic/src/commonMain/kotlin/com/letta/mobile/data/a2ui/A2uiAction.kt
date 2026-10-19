package com.letta.mobile.data.a2ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class A2uiAction(
    val name: String,
    val surfaceId: String,
    val context: JsonObject = JsonObject(emptyMap()),
    val conversationId: String? = null,
    val runId: String? = null,
    val turnId: String? = null,
    val actionId: String? = null,
    val raw: JsonObject = buildJsonObject {
        put("actionName", name)
        put("name", name)
        put("surfaceId", surfaceId)
        put("context", context)
        conversationId?.let { put("conversationId", it) }
        runId?.let { put("runId", it) }
        turnId?.let { put("turnId", it) }
        actionId?.let { put("actionId", it) }
    },
    /**
     * Which client code built this action. Never read from the wire: a
     * server-authored component (a plain Button, say) can choose any [name] and [context], but it
     * cannot choose this, so it is what tells a forged `tool_approval_response` from a real one.
     */
    val origin: A2uiActionOrigin = A2uiActionOrigin.SurfaceAuthored,
)

/** Who built an [A2uiAction]; see [A2uiAction.origin]. */
enum class A2uiActionOrigin {
    /** Name and context came from the server-authored surface (Button, form, list item, ...). */
    SurfaceAuthored,

    /** Built by the client's own ToolApprovalCard renderer from the user's tap on its affordances. */
    ToolApprovalCard,
}
