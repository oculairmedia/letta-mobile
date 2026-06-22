package com.letta.mobile.ui.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputSyntaxHighlighterTest {

    @Test
    fun `keywordRegex returns precompiled cached instance per language`() {
        val kotlinRegex1 = keywordRegex("kotlin")
        val kotlinRegex2 = keywordRegex("kotlin")

        val pythonRegex1 = keywordRegex("python")
        val pythonRegex2 = keywordRegex("python")

        val shellRegex1 = keywordRegex("bash")
        val shellRegex2 = keywordRegex("shell")

        val commonRegex1 = keywordRegex("unknown")
        val commonRegex2 = keywordRegex("plaintext")

        // Ensure they are the same instance (cached)
        assertSame("Kotlin regex should be cached", kotlinRegex1, kotlinRegex2)
        assertSame("Python regex should be cached", pythonRegex1, pythonRegex2)
        assertSame("Shell regex should be cached", shellRegex1, shellRegex2)
        assertSame("Common regex should be cached", commonRegex1, commonRegex2)

        // Sanity check that they match some known keywords
        assertTrue(kotlinRegex1.containsMatchIn("fun main()"))
        assertTrue(pythonRegex1.containsMatchIn("def my_func():"))
        assertTrue(shellRegex1.containsMatchIn("echo 'hello'"))
        assertTrue(commonRegex1.containsMatchIn("class MyClass"))
    }
}
