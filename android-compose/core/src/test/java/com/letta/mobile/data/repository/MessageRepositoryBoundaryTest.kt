package com.letta.mobile.data.repository

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Architecture guard for letta-mobile-u67jx.
 *
 * [MessageRepository] is a stateless HTTP helper. Live chat timeline state,
 * streaming sends, optimistic/local writes, and reconciliation belong to
 * `TimelineRepository`, so new message APIs should be reviewed deliberately.
 */
@Tag("unit")
class MessageRepositoryBoundaryTest {
    @Test
    fun `MessageRepository exposes only reviewed stateless HTTP operations`() {
        val source = repositorySource("MessageRepository.kt")
        val actualMethods = source.publicOverrideSuspendFunctions() + source.publicOverrideFunctions()

        assertEquals(
            setOf(
                "cancelBatch",
                "cancelMessage",
                "createBatch",
                "fetchConversationInspectorMessages",
                "fetchMessages",
                "fetchOlderMessages",
                "getMessagesPaged",
                "listBatchMessages",
                "listBatches",
                "resetMessages",
                "retrieveBatch",
                "searchMessages",
                "submitApproval",
            ),
            actualMethods,
        )
    }

    @Test
    fun `IMessageRepository does not reintroduce live timeline or streaming APIs`() {
        val source = repositorySource("api/IMessageRepository.kt")
        val forbiddenTerms = listOf(
            "sendMessage",
            "streamMessage",
            "observeTimeline",
            "observeMessages",
            "appendLocal",
            "appendExternalTransportLocal",
            "upsertExternalTransport",
            "reconcileExternalTransport",
        )

        val violations = forbiddenTerms.filter { it in source }

        assertTrue(
            "IMessageRepository must stay stateless; live timeline APIs belong to TimelineRepository: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `MessageRepository documentation names TimelineRepository as live chat owner`() {
        val source = repositorySource("MessageRepository.kt")

        assertTrue(source.contains("not** the chat timeline source of"))
        assertTrue(source.contains("TimelineRepository"))
        assertTrue(source.contains("Do not reintroduce"))
    }

    private fun repositorySource(relativePath: String): String = repositoryRoot()
        .resolve("core/src/main/java/com/letta/mobile/data/repository")
        .resolve(relativePath)
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

    private fun String.publicOverrideSuspendFunctions(): Set<String> = Regex(
        "override\\s+suspend\\s+fun\\s+(\\w+)\\s*\\("
    ).findAll(this).map { it.groupValues[1] }.toSet()

    private fun String.publicOverrideFunctions(): Set<String> = Regex(
        "override\\s+fun\\s+(\\w+)\\s*\\("
    ).findAll(this).map { it.groupValues[1] }.toSet()
}
