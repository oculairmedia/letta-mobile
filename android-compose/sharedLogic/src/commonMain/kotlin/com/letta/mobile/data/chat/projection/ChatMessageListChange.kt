package com.letta.mobile.data.chat.projection

enum class ChatMessageListChange {
    Full,
    AppendTail,
    ReplaceTail,

    /**
     * A deduped no-op tick: the projected message list is byte-identical to
     * the previous one. The timeline observer suppresses the UI state write for
     * this case, so this value should not reach rendering code in practice.
     */
    None,
}
