package com.letta.mobile.data.lanes


/** Supported purposes of an existing conversation; untagged and conflicting tags stay distinct. */
enum class ConversationPurpose { HOME, INBOX, WORK, UNTAGGED, UNKNOWN }

/** References carried by conversation tags. These are claims, never authority to read or send. */
data class ConversationLane(
    val purpose: ConversationPurpose,
    val beads: List<String>,
    val runs: List<String>,
) {
    /** Work records remain openable; they cannot become message destinations. */
    val canTarget: Boolean get() = purpose != ConversationPurpose.WORK && purpose != ConversationPurpose.UNKNOWN

    /** A conversation automatic selection may open for the human: never an inbox or work record. */
    val isHumanFallback: Boolean get() = canTarget && purpose != ConversationPurpose.INBOX

    companion object {
        /** Exact tags only: titles, class and message text cannot establish a lane or provenance. */
        fun fromTags(tags: List<String>): ConversationLane {
            val lanes = tags.filter { it.startsWith("lane:") }.distinct()
            val purpose = when (lanes.singleOrNull()) {
                "lane:home" -> ConversationPurpose.HOME
                "lane:inbox" -> ConversationPurpose.INBOX
                "lane:work" -> ConversationPurpose.WORK
                null -> if (lanes.isEmpty()) ConversationPurpose.UNTAGGED else ConversationPurpose.UNKNOWN
                else -> ConversationPurpose.UNKNOWN
            }
            return ConversationLane(purpose, tags.references("bead:"), tags.references("run:"))
        }

        private fun List<String>.references(prefix: String): List<String> =
            filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter(String::isNotBlank).distinct()
    }
}
