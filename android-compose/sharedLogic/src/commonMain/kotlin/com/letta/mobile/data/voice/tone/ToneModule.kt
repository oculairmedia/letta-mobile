package com.letta.mobile.data.voice.tone

/** One of the closed set of mood modules a tone cue may select, by the [wireName] a reply and a speech request use. */
enum class ToneModule(
    /** The module's name in a cue (`[warm]`) and in a speech request's `instructions`. */
    val wireName: String,
) {
    NEUTRAL("neutral"),
    WARM("warm"),
    EXCITED("excited"),
    SOMBRE("sombre"),
    QUIET("quiet"),
    FAST("fast"),
    SLOW("slow"),
    ;

    /** The module names. */
    companion object {
        /** The module named [wireName] exactly; null for anything else. */
        fun named(wireName: String): ToneModule? = entries.firstOrNull { it.wireName == wireName }
    }
}
