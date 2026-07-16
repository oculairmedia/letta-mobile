import re

with open("android-compose/feature-chat/src/test/java/com/letta/mobile/feature/chat/ChatMessageListScrollTest.kt", "r") as f:
    text = f.read()

text = text.replace("import org.junit.Test", "import org.junit.Test\nimport com.letta.mobile.feature.chat.screen.chatShouldLoadOlderMessages")

text += """
    @Test
    fun `shouldLoadOlderMessages returns false if no more older messages`() {
        assertFalse(
            chatShouldLoadOlderMessages(
                hasMoreOlderMessages = false,
                isLoadingOlderMessages = false,
                messagesEmpty = false,
                firstVisibleItemIndex = 10,
                renderItemsSize = 10
            )
        )
    }

    @Test
    fun `shouldLoadOlderMessages returns false if already loading`() {
        assertFalse(
            chatShouldLoadOlderMessages(
                hasMoreOlderMessages = true,
                isLoadingOlderMessages = true,
                messagesEmpty = false,
                firstVisibleItemIndex = 10,
                renderItemsSize = 10
            )
        )
    }

    @Test
    fun `shouldLoadOlderMessages returns false if empty`() {
        assertFalse(
            chatShouldLoadOlderMessages(
                hasMoreOlderMessages = true,
                isLoadingOlderMessages = false,
                messagesEmpty = true,
                firstVisibleItemIndex = 10,
                renderItemsSize = 10
            )
        )
    }

    @Test
    fun `shouldLoadOlderMessages triggers when scrolling near top`() {
        val renderItemsSize = 50

        // 50 items. Trigger distance is 3.
        // firstVisibleItemIndex + 3 >= 50 => firstVisibleItemIndex >= 47

        assertFalse(
            chatShouldLoadOlderMessages(
                hasMoreOlderMessages = true,
                isLoadingOlderMessages = false,
                messagesEmpty = false,
                firstVisibleItemIndex = 40,
                renderItemsSize = renderItemsSize
            )
        )

        assertTrue(
            chatShouldLoadOlderMessages(
                hasMoreOlderMessages = true,
                isLoadingOlderMessages = false,
                messagesEmpty = false,
                firstVisibleItemIndex = 47,
                renderItemsSize = renderItemsSize
            )
        )

        assertTrue(
            chatShouldLoadOlderMessages(
                hasMoreOlderMessages = true,
                isLoadingOlderMessages = false,
                messagesEmpty = false,
                firstVisibleItemIndex = 49,
                renderItemsSize = renderItemsSize
            )
        )
    }
"""

text = text.replace("}", "}\n", text.count("}") - 1) # Formatting

with open("android-compose/feature-chat/src/test/java/com/letta/mobile/feature/chat/ChatMessageListScrollTest.kt", "w") as f:
    f.write(text)
