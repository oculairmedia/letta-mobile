package com.letta.mobile.data.shellstate

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The shell and user state that the user and their agent both read and change: preferences, pins,
 * layout choices, drafts, history and settings, as one versioned document held by the host and
 * synced to every client.
 *
 * The host is the on-device runtime when the phone works alone, or a remote server host; the
 * document lives in the host's state store, never in client storage. Writes are proposals checked
 * against the entry's own revision, in the style of the host-held voice setup contract, so an
 * agent changing one entry never conflicts with a client changing another.
 *
 * Moving from one host to another (the phone's on-device runtime to a server) is one [export] and
 * one [import] of the whole document, never per-setting migrations.
 */
interface ShellStateDocument {
    /** The whole document as last confirmed by the host. */
    val snapshot: Flow<ShellStateSnapshot>

    /** Propose a new [value] for [key], or its removal with null, on behalf of [actor]. */
    suspend fun propose(
        key: String,
        value: JsonElement?,
        expectedRevision: Long?,
        actor: ShellStateActor,
    ): ShellStateProposal

    /** The document to hand to another host: its entries only, never client store values or secrets. */
    suspend fun export(): ShellStateExport

    /** Load [document] into this host under [policy]; refused when a non-empty target is not replaced. */
    suspend fun import(document: ShellStateExport, policy: ShellStateImportPolicy): ShellStateProposal
}

/** The schema of the shell state document. */
object ShellStateSchema {
    /** The schema version this build reads and writes; an import of a newer one is refused. */
    const val VERSION: Int = 1
}

/** The shell state document at one [documentRevision]. */
@Serializable
data class ShellStateSnapshot(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("document_revision") val documentRevision: Long,
    val entries: Map<String, ShellStateEntry>,
)

/** One entry with its own [revision], so unrelated writers never conflict. */
@Serializable
data class ShellStateEntry(
    val value: JsonElement,
    val revision: Long,
    @SerialName("updated_by") val updatedBy: ShellStateActor,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

/** Who wrote an entry, so the shell can show and revert what an agent changed. */
@Serializable
sealed interface ShellStateActor {
    /** The person, from one of their clients. */
    @Serializable
    @SerialName("user")
    data class User(@SerialName("client_id") val clientId: String) : ShellStateActor

    /** An agent, through the host. */
    @Serializable
    @SerialName("agent")
    data class Agent(@SerialName("agent_id") val agentId: String) : ShellStateActor
}

/** The answer to a proposed write or an import. */
sealed interface ShellStateProposal {
    /** Written; the entry or the document is now at [revision]. */
    data class Applied(val revision: Long) : ShellStateProposal

    /** The expected revision was stale; [current] is what the host holds. */
    data class Conflict(val current: ShellStateEntry?) : ShellStateProposal

    /** An unknown key, a schema the host cannot read, or a non-empty import target that was not replaced. */
    data class Rejected(val reason: String) : ShellStateProposal
}

/** The whole document as moved between hosts; secrets stay in host secure storage and move by their own path. */
@Serializable
data class ShellStateExport(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("exported_at_ms") val exportedAtMs: Long,
    @SerialName("source_host") val sourceHost: String,
    val entries: Map<String, JsonElement>,
)

/** What an import may do to the target host's document. */
enum class ShellStateImportPolicy {
    /** Only into a host whose document has no entries. */
    IntoEmpty,

    /** The user chose to replace the target's whole document; no per-entry merge. */
    Replace,
}
