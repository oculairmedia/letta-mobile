package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.chat.subagents.SubagentTodoLoader

/**
 * letta-mobile-ckmub.3.19: what the shared subagent rings over the desktop chat page read: the
 * conversation's registry [entries] and where a ring's todo sheet loads its checklist.
 */
internal data class DesktopChatSubagents(val entries: List<SubagentEntry>, val loader: SubagentTodoLoader)
