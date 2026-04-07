package com.letta.mobile.data.model

/**
 * Local configuration for a Letta instance.
 * NOT serializable - this is for local storage only (DataStore).
 */
data class LettaConfig(
    val id: String,
    val mode: Mode,
    val serverUrl: String,
    val accessToken: String? = null,
) {
    enum class Mode {
        CLOUD,
        SELF_HOSTED
    }
}
