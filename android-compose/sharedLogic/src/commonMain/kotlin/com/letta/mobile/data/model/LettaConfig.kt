package com.letta.mobile.data.model

/**
 * Local configuration for a Letta-compatible backend.
 *
 * Persistence and secret handling are platform concerns. This portable value
 * type is shared so every platform can select the same backend mode without
 * depending on Android DataStore, Room, or UI code.
 */
data class LettaConfig(
    val id: String,
    val mode: Mode,
    val serverUrl: String,
    val accessToken: String? = null,
) {
    enum class Mode {
        CLOUD,
        SELF_HOSTED,
        LOCAL,
    }
}
