package com.letta.mobile.util

/** Says once per list that a host's conversation rows were left out because this client cannot represent them. */
internal fun reportSkippedConversationRows(skipped: Int, kept: Int, firstError: String?) {
    Telemetry.event(
        "ConversationList", "rows_skipped",
        "skipped" to skipped, "kept" to kept, "firstError" to firstError.orEmpty(),
        level = Telemetry.Level.WARN,
    )
}
