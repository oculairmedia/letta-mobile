package com.letta.mobile.bot.runtime

import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.MessageCreateRequest
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DefaultLettaRuntimeClientTest : WordSpec({
    "DefaultLettaRuntimeClient" should {
        "submit approval result with non-streaming continuation payload" {
            val messageApi = mockk<MessageApi>()
            var captured: MessageCreateRequest? = null
            coEvery { messageApi.sendConversationMessage(any(), any()) } answers {
                captured = secondArg()
                ByteReadChannel("data: [DONE]\n\n")
            }

            val client = DefaultLettaRuntimeClient(
                messageApi = messageApi,
                conversationApi = mockk(relaxed = true),
                blockApi = mockk(relaxed = true),
                toolApi = mockk(relaxed = true),
            )

            runBlocking {
                client.submitToolResult(
                    conversationId = "conversation-1",
                    toolCallId = "tool-call-1",
                    toolReturn = "Battery: 80%",
                )
            }

            coVerify(exactly = 1) { messageApi.sendConversationMessage("conversation-1", any()) }
            captured!!.streaming shouldBe false
            val approvalPayload = Json.parseToJsonElement(captured!!.messages!!.single().toString()).jsonObject
            approvalPayload["approve"]!!.toString() shouldBe "true"
            val approvals = approvalPayload["approvals"]!!.jsonArray
            approvals.single().jsonObject["tool_call_id"]!!.jsonPrimitive.content shouldBe "tool-call-1"
            approvals.single().jsonObject["tool_return"]!!.jsonPrimitive.content shouldContain "Battery: 80%"
        }
    }
})
