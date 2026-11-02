package com.letta.mobile.data.controller.extras.messaging

/** Optional recipient addressing and task correlation for an agent message. */
data class IrohMessageOptions(
    val conversationId: String? = null,
    val lane: String? = null,
    val correlation: Map<String, String>? = null,
)
