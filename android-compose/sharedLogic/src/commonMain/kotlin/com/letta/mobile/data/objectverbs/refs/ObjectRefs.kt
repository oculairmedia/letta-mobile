package com.letta.mobile.data.objectverbs.refs

import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.projects.ProjectKey

/** Reference an existing agent in the caller's trusted source. */
fun ObjectRef.Companion.agent(id: AgentId): ObjectRef = ObjectRef("agent", id.value)
/** Reference an existing resolved conversation, never a bare default. */
fun ObjectRef.Companion.conversation(id: ConversationId): ObjectRef = ObjectRef("conversation", id.value)
/** Reference a project by its existing domain ID. */
fun ObjectRef.Companion.project(id: ProjectId): ObjectRef = ObjectRef("project", id.value)
/** Reference the Projects catalog's existing host identifier. */
fun ObjectRef.Companion.project(key: ProjectKey): ObjectRef = ObjectRef("project", key.value)
/** Unwrap a canvas ID at the canonical reference boundary. */
fun ObjectRef.Companion.canvas(id: CanvasId): ObjectRef = ObjectRef("canvas", id.value)
/** HomeShortcut.name remains the stable shortcut identity. */
fun ObjectRef.Companion.shortcut(shortcut: HomeShortcut): ObjectRef = ObjectRef("shortcut", shortcut.name)
