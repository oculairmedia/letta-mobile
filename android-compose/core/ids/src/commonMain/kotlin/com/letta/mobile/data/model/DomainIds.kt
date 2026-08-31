package com.letta.mobile.data.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Domain identity types wrapped in inline value classes.
 *
 * Each ID type is a zero-overhead wrapper around [String] that prevents
 * accidental interchange between different ID domains. Serialization treats
 * each value class as its underlying [String] type.
 *
 * Platform persistence integration belongs in platform modules. This module
 * stays free of AndroidX, Room, DataStore, and UI dependencies.
 */
@JvmInline
@Serializable
value class AgentId(val value: String) {
    override fun toString(): String = value
}

/** Classifies transient workers created by Letta Code during a run. */
fun AgentId.isLettaCodeEphemeralWorker(): Boolean =
    value.startsWith(LETTA_CODE_EPHEMERAL_WORKER_ID_PREFIX)

private const val LETTA_CODE_EPHEMERAL_WORKER_ID_PREFIX = "agent-local-worker-"

@JvmInline
@Serializable
value class ProjectId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ConversationId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ToolId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class BlockId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class FolderId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ProviderId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class IdentityId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class McpServerId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class GroupId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class HostId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ProviderDefinitionId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ProviderInstanceId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ProviderFieldId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ModelRouteId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class CatalogRevision(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ProviderRevision(val value: String) {
    override fun toString(): String = value
}
