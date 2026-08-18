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
    val localModelPath: String? = null,
    val localModelHandle: String? = null,
    val localModelRuntime: String? = null,
    val localModelAccelerator: String? = null,
    val localModelMaxTokens: Int? = null,
    /**
     * Optional OpenAI-compatible endpoint for the embedded runtime: the
     * letta.js agent loop (memfs, tool execution, conversations) stays on
     * device while LLM calls go to this endpoint instead of the on-device
     * model — native tool calling included. When set, no .litertlm model is
     * required.
     */
    val localProviderBaseUrl: String? = null,
    val localProviderApiKey: String? = null,
    val localProviderModel: String? = null,
    /**
     * letta-mobile-hhp6r: a shelf for the most recently known remote
     * backend's connection details, kept in sync whenever [BackendConfigPolicy]
     * blanks [serverUrl] on a switch to [Mode.LOCAL] (see
     * `BackendConfigPolicy.migrateStaleLocalServerUrl`). Switching back to a
     * remote [Mode] with a blank [serverUrl] restores from here (see
     * `BackendConfigPolicy.restoreParkedRemoteBackend`) instead of re-prompting
     * for the URL and token.
     *
     * These fields are never read by routing or transport-binding code —
     * `Mode.LOCAL` stays authoritative over `serverUrl`/`isIrohBackend()` (see
     * `BackendKind`'s KDoc and letta-mobile-9v9nu). They exist purely to avoid
     * losing data the user already entered.
     */
    val parkedServerUrl: String? = null,
    val parkedAccessToken: String? = null,
) {
    enum class Mode {
        CLOUD,
        SELF_HOSTED,
        LOCAL,
    }
}
