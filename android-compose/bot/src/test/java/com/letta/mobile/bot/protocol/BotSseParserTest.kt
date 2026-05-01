package com.letta.mobile.bot.protocol

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

@Tag("unit")
class BotSseParserTest : WordSpec({
    "BotSseParser" should {
        "parse data events followed by done marker" {
            val payload = """
                data: {"text":"hello","conversation_id":"c1","done":false}

                data: [DONE]

            """.trimIndent()

            val chunks = runBlocking {
                BotSseParser.parse(ByteReadChannel(payload)).toList()
            }

            chunks shouldHaveSize 2
            chunks[0].text shouldBe "hello"
            chunks[0].conversationId shouldBe "c1"
            chunks[0].done shouldBe false
            chunks[1].done shouldBe true
            chunks[1].text shouldBe null
            chunks[1].event shouldBe null
        }
    }
})
