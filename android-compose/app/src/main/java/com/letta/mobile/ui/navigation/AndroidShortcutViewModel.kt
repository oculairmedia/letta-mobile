package com.letta.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin accessor so [AdaptiveScaffold] can step conversations and agents from the shared
 * keymap (letta-mobile-k5nbq.4) without taking repository constructors.
 */
@HiltViewModel
class AndroidShortcutViewModel @Inject constructor(
    conversations: IAllConversationsRepository,
    agents: IAgentRepository,
) : ViewModel() {
    val conversations: StateFlow<List<Conversation>> = conversations.conversations
    val agents: StateFlow<List<Agent>> = agents.agents
}
