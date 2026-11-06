package com.letta.mobile.data.objectverbs

import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.projects.ProjectKey
import kotlinx.serialization.Serializable

/**
 * Canonical identity within the caller's bound source, independent of lens or view mount.
 * Wire [kind] and [id] stay open strings so future kinds decode. Known callers use the typed
 * factories; a raw reference grants no authority and an unknown kind renders nothing.
 */
@Serializable
data class ObjectRef(val kind: String, val id: String) {
    /** Adapters over existing IDs; these do not mint or reinterpret domain identity. */
    companion object {
        /** Reference an existing agent. */
        fun agent(id: AgentId): ObjectRef = ObjectRef("agent", id.value)

        /** Reference an existing, source-qualified conversation (never an ambiguous bare default). */
        fun conversation(id: ConversationId): ObjectRef = ObjectRef("conversation", id.value)

        /** Reference a project by its domain ID. */
        fun project(id: ProjectId): ObjectRef = ObjectRef("project", id.value)

        /** Reference the Projects catalog's existing host identifier. */
        fun project(key: ProjectKey): ObjectRef = ObjectRef("project", key.value)

        /** Unwrap the ID only here; CanvasId's persisted object representation is unchanged. */
        fun canvas(id: CanvasId): ObjectRef = ObjectRef("canvas", id.value)

        /** HomeShortcut.name is the existing stable shortcut identity. */
        fun shortcut(shortcut: HomeShortcut): ObjectRef = ObjectRef("shortcut", shortcut.name)
    }
}
