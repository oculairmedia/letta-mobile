package com.letta.mobile.feature.chat.coordination

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * letta-mobile-ah1ng source contract.
 *
 * Every projection publication inside [ChatTimelineObserver] must route
 * through terminal reconciliation
 * ([ChatRunExpansionState.reconcileCollapsedRunsOnProjection]). The original
 * defect shipped as a raw `uiState.value = prev.copy(...)` publication that
 * silently bypassed collapse reconciliation, so this contract fails closed:
 * any new unwrapped publication inside the timeline collector (or an
 * unwrapped presence-clear in the sync-event collector) breaks the build.
 */
class ChatTimelineObserverCollapseContractTest {

    private val source = String(Files.readAllBytes(observerSource()), StandardCharsets.UTF_8)

    @Test
    fun `every projection publication inside the timeline collector routes through reconciliation`() {
        val collectRegion = braceMatchedRegion(source, "flow.collect { timeline ->")
        val publications = uiStateAssignments(collectRegion)
        assertTrue(
            "expected projection publications inside flow.collect; contract region is empty",
            publications.isNotEmpty(),
        )
        publications.forEachIndexed { index, statement ->
            assertTrue(
                "projection publication #$index bypasses reconcileCollapsedRunsOnProjection:\n$statement",
                statement.contains("reconcileCollapsedRunsOnProjection("),
            )
        }
    }

    @Test
    fun `reconcile-error presence clear routes through reconciliation`() {
        val eventsRegion = braceMatchedRegion(source, "loop.events.collect { ev ->")
        assertTrue(
            "ReconcileError branch missing from sync-event collector",
            eventsRegion.contains("is TimelineSyncEvent.ReconcileError"),
        )
        val branch = braceMatchedRegion(eventsRegion, "is TimelineSyncEvent.ReconcileError ->")
        val publications = uiStateAssignments(branch)
        assertTrue(publications.isNotEmpty())
        publications.forEach { statement ->
            assertTrue(
                "ReconcileError publication bypasses reconcileCollapsedRunsOnProjection:\n$statement",
                statement.contains("reconcileCollapsedRunsOnProjection("),
            )
        }
    }

    @Test
    fun `observer declares and invokes the reconciliation hook`() {
        assertTrue(
            "ChatTimelineObserver must declare the reconcileCollapsedRunsOnProjection hook",
            source.contains("private val reconcileCollapsedRunsOnProjection:"),
        )
        val references = Regex("reconcileCollapsedRunsOnProjection").findAll(source).count()
        val invocations = Regex("reconcileCollapsedRunsOnProjection\\(").findAll(source).count()
        assertTrue(
            "expected >= 4 reconciliation references (declaration + 3 production sites), found $references",
            references >= 4,
        )
        assertTrue(
            "expected all 3 production publications to invoke reconciliation, found $invocations",
            invocations >= 3,
        )
    }

    /**
     * Returns the balanced-brace region that starts at the line containing
     * [marker], inclusive of the closing brace.
     */
    private fun braceMatchedRegion(text: String, marker: String): String {
        val markerIndex = text.indexOf(marker)
        assertTrue("marker not found: $marker", markerIndex >= 0)
        var depth = 0
        var started = false
        var i = text.indexOf('{', markerIndex)
        assertTrue("no brace after marker: $marker", i >= 0)
        val start = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> {
                    depth++
                    started = true
                }
                '}' -> {
                    depth--
                    if (started && depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after marker: $marker")
    }

    /** Captures each full `uiState.value = <expression>` assignment statement. */
    private fun uiStateAssignments(region: String): List<String> {
        val statements = mutableListOf<String>()
        var idx = region.indexOf(ASSIGNMENT_MARKER)
        while (idx >= 0) {
            var depth = 0
            var started = false
            var i = idx
            while (i < region.length) {
                when (region[i]) {
                    '(' -> {
                        depth++
                        started = true
                    }
                    ')' -> depth--
                }
                if (started && depth == 0) break
                i++
            }
            statements.add(region.substring(idx, (i + 1).coerceAtMost(region.length)))
            idx = region.indexOf(ASSIGNMENT_MARKER, i + 1)
        }
        return statements
    }

    private fun observerSource(): Path {
        val userDir = Path.of(System.getProperty("user.dir"))
        return listOf(
            userDir.resolve("src/main/java/com/letta/mobile/feature/chat/coordination/ChatTimelineObserver.kt"),
            userDir.resolve("feature-chat/src/main/java/com/letta/mobile/feature/chat/coordination/ChatTimelineObserver.kt"),
        ).firstOrNull { it.exists() }
            ?: error("ChatTimelineObserver.kt not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ASSIGNMENT_MARKER = "uiState.value ="
    }
}
