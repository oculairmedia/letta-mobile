package com.letta.mobile.data.repository

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Atomic persistence for [LastChatSelection].
 *
 * The selection used to be written as THREE independent key/value puts
 * (`last_chat_agent_id`, `last_chat_agent_name`, `last_chat_conversation_id`).
 * Any interleaving, crash, or partially-failed write between those puts leaves
 * a *torn* triple on disk: an agentId from one selection paired with the name
 * and/or conversationId of another. That state is representable and does occur
 * — and it renders a confidently wrong agent name, or resumes the wrong
 * conversation, which is strictly worse than showing nothing.
 *
 * The fix is to make the torn state unrepresentable in storage: the whole
 * triple is serialized into ONE string under ONE key, so a write either lands
 * whole or not at all. `core:data` keeps only the binding (read/write that
 * string); the record shape, the serialization and the identity fence are
 * pure functions here.
 *
 * Lives in `commonMain` per AGENTS.md: platform-neutral state transforms are
 * must-be-shared, so production and every test substitute run one
 * implementation instead of drifting.
 */
object LastChatSelectionStorage {

    /** Single key holding the serialized triple. Atomic unit of persistence. */
    const val KEY: String = "last_chat_selection_v1"

    /** Telemetry tag; grep logcat for `Telemetry/LastChatSelectionStorage`. */
    const val TELEMETRY_TAG: String = "LastChatSelectionStorage"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Record(
        val agentId: String,
        val agentName: String? = null,
        val conversationId: String? = null,
    )

    /**
     * Serialize a selection into the single persisted string.
     *
     * Applies the identity fence first, so a caller cannot smuggle a torn
     * triple in through the front door either.
     */
    fun serialize(selection: LastChatSelection): String? {
        val fenced = fenceIdentity(selection, source = "serialize") ?: return null
        return json.encodeToString(
            Record(
                agentId = fenced.agentId,
                agentName = fenced.agentName,
                conversationId = fenced.conversationId,
            ),
        )
    }

    /**
     * Parse the single persisted string back into a selection.
     *
     * Returns `null` — absent, not a guess — for anything that is not provably
     * a whole, self-consistent record. A malformed blob is a signal that the
     * write path or the store is broken, and reconstructing something plausible
     * from it would hide exactly the defect this bead exists to kill.
     */
    fun deserialize(stored: String?): LastChatSelection? {
        val raw = stored?.takeIf { it.isNotBlank() } ?: return null
        val record = try {
            json.decodeFromString<Record>(raw)
        } catch (e: SerializationException) {
            Telemetry.event(
                TELEMETRY_TAG,
                "lastChatSelectionMalformedDiscarded",
                "reason" to "undeserializable",
                "errorClass" to (e::class.simpleName ?: "SerializationException"),
                "rawLength" to raw.length,
                level = Telemetry.Level.WARN,
            )
            return null
        }
        return fenceIdentity(
            LastChatSelection(
                agentId = record.agentId,
                agentName = record.agentName,
                conversationId = record.conversationId,
            ),
            source = "deserialize",
        )
    }

    /**
     * Identity fence: [LastChatSelection.agentName] and
     * [LastChatSelection.conversationId] are meaningful ONLY as attributes of
     * this record's own [LastChatSelection.agentId].
     *
     * A blank agentId therefore invalidates the entire record — there is no
     * identity for the name and conversation to hang off, so keeping them would
     * be keeping an orphan half of a torn triple. Blank name / conversationId
     * normalize to `null` (absent), which is honest.
     *
     * Rejections are logged loudly rather than silently swallowed.
     */
    fun fenceIdentity(selection: LastChatSelection?, source: String): LastChatSelection? {
        if (selection == null) return null
        val agentId = selection.agentId.takeIf { it.isNotBlank() }
        if (agentId == null) {
            Telemetry.event(
                TELEMETRY_TAG,
                "lastChatSelectionIdentityFenceRejected",
                "source" to source,
                "reason" to "blankAgentId",
                "hadAgentName" to (selection.agentName?.isNotBlank() == true),
                "hadConversationId" to (selection.conversationId?.isNotBlank() == true),
                level = Telemetry.Level.WARN,
            )
            return null
        }
        return LastChatSelection(
            agentId = agentId,
            agentName = selection.agentName?.takeIf { it.isNotBlank() },
            conversationId = selection.conversationId?.takeIf { it.isNotBlank() },
        )
    }

}
