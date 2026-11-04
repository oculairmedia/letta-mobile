package com.letta.mobile.data.controller.extras.messaging

/** Optional recipient addressing and task correlation for an agent message. */
data class IrohMessageOptions(
    /** Explicit target conversation; when set it wins and [lane] is ignored. */
    val conversationId: String? = null,
    /** Lane to address on the recipient (`home` or `inbox`); null means the recipient's inbox. */
    val lane: String? = null,
    /** Free-form string correlation (for example a bead id) carried to the recipient unchanged. */
    val correlation: Map<String, String>? = null,
    /** Caller-supplied idempotency ID; reuse it unchanged when retrying the same message. */
    val msgId: String? = null,
)
