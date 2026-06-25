package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage

enum class ChatMessageListChange {
    Full,
    AppendTail,
    ReplaceTail,

    /**
     * A deduped no-op tick: the projected message list is byte-identical to
     * the previous one. The timeline observer suppresses the UI state write for
     * this case, so this value should not reach rendering code in practice.
     */
    None;

    companion object {
        /**
         * Computes the change between two projected chat message lists.
         * Does not do full deep equality checks on large prefixes if we can
         * establish that it's a tail replacement or append via identity checks 
         * or size constraints, but will fall back to equality if needed.
         */
        fun compute(previous: List<UiMessage>, next: List<UiMessage>): ChatMessageListChange {
            if (previous === next) return None
            if (previous.isEmpty() && next.isEmpty()) return None
            if (previous.isEmpty() || next.isEmpty()) return Full

            // Same size checks
            if (previous.size == next.size) {
                if (previous == next) return None
                
                // Check if it's a tail replacement (only the last item changed)
                val samePrefix = previous.subList(0, previous.size - 1) == next.subList(0, next.size - 1)
                if (samePrefix) {
                    return ReplaceTail
                }
                return Full
            }

            // Next list is exactly one item larger. Check for append tail.
            if (next.size == previous.size + 1) {
                val samePrefix = previous == next.subList(0, previous.size)
                if (samePrefix) {
                    return AppendTail
                }
            }

            return Full
        }
    }
}
