package com.letta.mobile.data.clientstore

/**
 * Everything a client is allowed to persist. Client persistence APIs are usable only in packages with
 * a `clientstore` segment (the `ClientPersistenceOutsideClientStore` guardrail), and what those
 * packages keep is declared here. Keep it tiny: anything the user creates or chooses is host state.
 */
object ClientStoreEntries {
    // Connection bootstrap.
    val BackendConfigs = bootstrap("configs", "Backend configs and their tokens, Android")
    val ActiveBackendConfig = bootstrap("active_config_id", "Which backend config this device connects with, Android")
    val DesktopBackendConfig = bootstrapFamily("letta.config.", "Backend address, mode, token and recent backends, desktop")
    val WebBackend = bootstrapFamily("backend.", "Backend address and mode, web")
    val DesktopLocalRuntime = bootstrapFamily(
        "letta.desktop.",
        "Install id, data directory and launch settings needed to start the desktop's local host",
    )
    val DesktopIrohIdentity = bootstrap("iroh-client-identity.enc", "This desktop's Iroh client identity key")
    val NotebookIdentity = bootstrap("notebook-identity", "This device's notebook peer id")
    val AutomationImport = bootstrap("letta_automation", "A debug build's one-shot backend config import")

    // Disposable caches.
    val ContextReadings = cache("context_readings_v1", "Context token readings the host recomputes")
    val MascotIdentity = cacheFamily("agent.", "Avatar style per agent, cached from the host's agent metadata")
    val ShellStateMirror = cache(
        "shell_state_mirror_v1",
        "The last shell state document the host answered, shown until the host answers again; never a base for an edit",
    )
    val ChannelSyncState = cache("channel_sync_state", "Notification dedupe cursors; a wipe can repeat a notification")
    val NotificationLaunchTokens = cache("letta_notification_launch_tokens", "Targets of notification taps not yet opened")

    // Device facts.
    val VoiceHostAudioAck = deviceFact(
        "voice.host.audioAck",
        "Consent to stream this device's microphone through the host is given on this device for its own microphone",
        "Host audio acknowledgement",
    )

    /** Every declared entry, in declaration order. */
    val all: List<ClientStoreEntry> = listOf(
        BackendConfigs, ActiveBackendConfig, DesktopBackendConfig, WebBackend, DesktopLocalRuntime, DesktopIrohIdentity,
        NotebookIdentity, AutomationImport, ContextReadings, MascotIdentity, ShellStateMirror, ChannelSyncState, NotificationLaunchTokens,
        VoiceHostAudioAck,
    )

    /** The disposable caches, all wiped by [clearAll]. */
    val caches: List<ClientStoreEntry> = all.filter { it.kind == ClientStoreKind.DisposableCache }

    /** The declaration [key] belongs to: the exact key first, else the longest matching family. */
    fun declarationOf(key: String): ClientStoreEntry? =
        all.firstOrNull { !it.isPrefix && it.name == key }
            ?: all.filter { it.isPrefix && it.matches(key) }.maxByOrNull { it.name.length }

    /** Wipe every disposable cache; nothing the user created or chose is lost. */
    suspend fun clearAll(caches: List<DisposableCache>) = caches.forEach { it.clearAll() }

    private fun bootstrap(name: String, summary: String) = ClientStoreEntry(name, ClientStoreKind.ConnectionBootstrap, summary)

    private fun bootstrapFamily(prefix: String, summary: String) =
        ClientStoreEntry(prefix, ClientStoreKind.ConnectionBootstrap, summary, isPrefix = true)

    private fun cache(name: String, summary: String) = ClientStoreEntry(name, ClientStoreKind.DisposableCache, summary)

    private fun cacheFamily(prefix: String, summary: String) =
        ClientStoreEntry(prefix, ClientStoreKind.DisposableCache, summary, isPrefix = true)

    private fun deviceFact(name: String, justification: String, summary: String) =
        ClientStoreEntry(name, ClientStoreKind.DeviceFact(justification), summary)
}
