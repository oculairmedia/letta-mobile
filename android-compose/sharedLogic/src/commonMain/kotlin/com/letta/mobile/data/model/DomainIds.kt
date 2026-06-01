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

