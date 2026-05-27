package com.letta.mobile.data.timeline

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class TimelineSyncStreamSubscriberPolicyTest {
    @Test
    fun `stream subscriber does not key approval recovery off stop reason`() {
        val source = timelineSource("TimelineSyncStreamSubscriber.kt")

        assertFalse(source.contains("requires_approval"))
        assertFalse(source.contains("postApprovalReconcile"))
    }

    private fun timelineSource(fileName: String): String = repositoryRoot()
        .resolve("core/src/main/java/com/letta/mobile/data/timeline")
        .resolve(fileName)
        .readText()

    private fun repositoryRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (cursor.name != "android-compose" && cursor.parent != null) {
            cursor = cursor.parent
        }
        require(Files.exists(cursor.resolve("core/build.gradle.kts"))) {
            "Could not locate android-compose root from ${System.getProperty("user.dir")}"
        }
        return cursor
    }
}
