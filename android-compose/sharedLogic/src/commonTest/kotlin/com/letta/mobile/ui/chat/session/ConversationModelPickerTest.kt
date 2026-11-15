package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ConversationModelPickerTest {
    @Test
    fun toolsetControlRequiresBothAgentAndConversation() = runTest {
        val focus = MutableStateFlow(ConversationModelFocus(conversationId = ConversationId("conv-1")))
        val host = object : ConversationModelHost {
            override val backend: ConversationModelBackend? = null
            override val isClosed = false
            override val focus = focus
            override fun showModel(update: ConversationModelUpdate) = Unit
        }
        val models = ConversationModels(backgroundScope, host)
        val control = ModelControlSession(AdminRpcInvoker { _, _ -> error("No RPC expected") })
        val binding = conversationModelPicker(control, models, null) {}
        assertNull(binding.toolsets?.invoke(backgroundScope))
        focus.value = ConversationModelFocus(ConversationId("conv-1"), AgentId("agent-1"))
        assertNotNull(binding.toolsets?.invoke(backgroundScope))
        focus.value = ConversationModelFocus(agentId = AgentId("agent-1"))
        assertNull(binding.toolsets?.invoke(backgroundScope))
    }
}
