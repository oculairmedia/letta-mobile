package com.letta.mobile.util

/** Says once per list that a host's conversation rows were left out because this client cannot represent them. */
internal fun reportSkippedConversationRows(detail: String) {
    Telemetry.event(
        "ConversationList", "rows_skipped", "detail" to detail,
        level = Telemetry.Level.WARN,
    )
}
