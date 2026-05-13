package com.letta.mobile.data.model

import androidx.room.TypeConverter
import kotlinx.serialization.Serializable

/**
 * Domain identity types wrapped in [@JvmInline] value classes.
 *
 * Each ID type is a zero-overhead wrapper around [String] that prevents
 * accidental interchange between different ID domains (e.g., passing an
 * [AgentId] where a [ConversationId] is expected). At runtime these are
 * represented as plain Strings with no allocation overhead.
 *
 * Serialization: each value class carries [@Serializable] so kotlinx.serialization
 * treats it as its underlying [String] type transparently. [@SerialName]
 * annotations on parent data class properties continue to work unchanged.
 *
 * Room: [DomainIdConverters] provides bidirectional [TypeConverter]s for
 * all ID types so Room DAOs can store them as TEXT columns without additional
 * migration steps.
 *
 * See: letta-mobile-925m.1
 */

// ─── AgentId ──────────────────────────────────────────────────────────────

@JvmInline
@Serializable
value class AgentId(val value: String) {
    override fun toString(): String = value
}

// ─── ProjectId ────────────────────────────────────────────────────────────

@JvmInline
@Serializable
value class ProjectId(val value: String) {
    override fun toString(): String = value
}

// ─── ToolId ───────────────────────────────────────────────────────────────

@JvmInline
@Serializable
value class ToolId(val value: String) {
    override fun toString(): String = value
}

// ─── BlockId ──────────────────────────────────────────────────────────────

@JvmInline
@Serializable
value class BlockId(val value: String) {
    override fun toString(): String = value
}

// ─── Room TypeConverters ──────────────────────────────────────────────────

class DomainIdConverters {
    @TypeConverter
    fun agentIdToString(id: AgentId): String = id.value

    @TypeConverter
    fun stringToAgentId(value: String): AgentId = AgentId(value)

    @TypeConverter
    fun projectIdToString(id: ProjectId): String = id.value

    @TypeConverter
    fun stringToProjectId(value: String): ProjectId = ProjectId(value)

    @TypeConverter
    fun toolIdToString(id: ToolId): String = id.value

    @TypeConverter
    fun stringToToolId(value: String): ToolId = ToolId(value)

    @TypeConverter
    fun blockIdToString(id: BlockId): String = id.value

    @TypeConverter
    fun stringToBlockId(value: String): BlockId = BlockId(value)
}
