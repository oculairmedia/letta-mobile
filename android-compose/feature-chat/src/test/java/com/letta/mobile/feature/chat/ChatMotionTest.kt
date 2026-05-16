package com.letta.mobile.feature.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMotionTest {

    @Test
    fun `chat motion timings keep streaming faster than user initiated expansion`() {
        assertTrue(ChatMotion.StreamingSizeMillis < ChatMotion.ContentSizeMillis)
        assertTrue(ChatMotion.FastFadeOutMillis < ChatMotion.ExitMillis)
        assertTrue(ChatMotion.ExitMillis < ChatMotion.EnterMillis)
        assertTrue(ChatMotion.ChipMillis <= ChatMotion.ContentSizeMillis)
    }

    /**
     * Regression guard for the run-block expand/collapse animation.
     *
     * History: feat(chat) 50efc445 introduced AnimatedVisibility around the
     * RunBlock collapsible body; a later stabilization pass (74314380) rewrote
     * the structure to fix a mid-stream sibling swap and silently dropped the
     * animation wrapper while leaving the explanatory comment behind. The user
     * reported "expressive animations on opening and closing sections in the
     * chat timeline regressed".
     *
     * This test pins the contract structurally so a future refactor cannot
     * silently lose motion again: RunBlock.kt must wire its expand/collapse
     * path through AnimatedContent + ChatMotion.expandEnter / expandExit.
     */
    @Test
    fun `RunBlock retains AnimatedContent expand collapse wiring`() {
        val source = locateSource("RunBlock.kt").readText()
        assertTrue(
            "RunBlock.kt must import AnimatedContent for run expand/collapse motion",
            source.contains("import androidx.compose.animation.AnimatedContent"),
        )
        assertTrue(
            "RunBlock.kt must use AnimatedContent to animate the collapse state",
            source.contains("AnimatedContent("),
        )
        assertTrue(
            "RunBlock.kt must wire ChatMotion.expandEnter into the run animation",
            source.contains("ChatMotion.expandEnter("),
        )
        assertTrue(
            "RunBlock.kt must wire ChatMotion.expandExit into the run animation",
            source.contains("ChatMotion.expandExit("),
        )
        assertTrue(
            "RunBlock.kt must drive SizeTransform with ChatMotion.contentSizeSpec",
            source.contains("ChatMotion.contentSizeSpec"),
        )
    }

    private fun locateSource(fileName: String): File {
        val candidates = listOf(
            "feature-chat/src/main/java/com/letta/mobile/feature/chat/$fileName",
            "android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/$fileName",
            "../feature-chat/src/main/java/com/letta/mobile/feature/chat/$fileName",
        )
        for (relative in candidates) {
            val candidate = File(relative).absoluteFile
            if (candidate.isFile) return candidate
        }
        // Walk up from the working directory to find the source file as a
        // fallback so the test works under whatever CWD Gradle picks.
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            val match = current.walkTopDown()
                .firstOrNull { file ->
                    file.isFile &&
                        file.name == fileName &&
                        file.toPath().iterator().asSequence().map { it.toString() }.toList().takeLast(2) == listOf("chat", fileName)
                }
            if (match != null) return match
            dir = current.parentFile
        }
        error("Could not locate $fileName from CWD ${File(".").absolutePath}")
    }
}
