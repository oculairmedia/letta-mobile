package com.letta.mobile.data.model

import kotlinx.serialization.Serializable

/**
 * Domain identity types wrapped in [@JvmInline] value classes.
 *
 * Each ID type is a zero-overhead wrapper around [String] that prevents
 * accidental interchange between different ID domains (e.g., passing an
 * [AgentId] where a [ProjectId] is expected). At runtime these are represented
 * as plain Strings with no allocation overhead.
 *
 * Serialization: each value class carries [Serializable] so kotlinx.serialization
 * treats it as its underlying [String] type transparently. SerialName annotations
 * on parent data class properties continue to work unchanged.
 *
 * Room integration lives in :core, keeping this module free of AndroidX and
 * persistence-framework dependencies.
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
