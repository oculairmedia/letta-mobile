package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeMessageApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageApiChatGatewayTest {
    @Test
    fun listConversationsUsesSharedGatewayLimitContract() = runTest {
        val conversationApi = FakeConversationApi().apply {
            conversations += TestData.conversation(id = "conv-1")
        }
        val gateway = MessageApiChatGateway(conversationApi, FakeMessageApi())

        val conversations = gateway.listConversations(limit = 12)

        assertEquals(listOf("conv-1"), conversations.map { it.id.value })
        assertEquals(listOf(12), conversationApi.listLimits)
    }

    @Test
    fun getConversationDelegatesToConversationApi() = runTest {
        val conversationApi = FakeConversationApi().apply {
            conversations += TestData.conversation(id = "conv-1")
        }
        val gateway = MessageApiChatGateway(conversationApi, FakeMessageApi())

        val conversation = gateway.getConversation("conv-1")

        assertEquals("conv-1", conversation.id.value)
        assertEquals(listOf("getConversation:conv-1"), conversationApi.calls)
    }

    @Test
    fun timelineMethodsDelegateToMessageApiTransport() = runTest {
        val messageApi = FakeMessageApi()
        val gateway = MessageApiChatGateway(FakeConversationApi(), messageApi)

        gateway.sendConversationMessage("conv-1", MessageCreateRequest(input = "hello")).toList()
        gateway.listConversationMessages("conv-1", limit = 5, after = "m1", order = "asc")
        gateway.listAgentMessages("agent-1", limit = 3, order = "desc", conversationId = "conv-1")

        assertEquals("conv-1", messageApi.lastStreamConversationId)
        assertEquals(MessageCreateRequest(input = "hello"), messageApi.lastStreamRequest)
        assertEquals(
            listOf(
                "sendConversationMessage:conv-1",
                "listConversationMessages:conv-1",
                "listMessages:agent-1",
            ),
            messageApi.calls,
        )
    }

    @Test
    fun conversationApiFailuresMapToTimelineTransportFailures() {
        val gateway = MessageApiChatGateway(
            FakeConversationApi().apply { shouldFail = true },
            FakeMessageApi(),
        )

        val error = assertThrows(TimelineTransportHttpException::class.java) {
            runTest {
                gateway.listConversations()
            }
        }

        assertEquals(500, error.code)
    }
}
