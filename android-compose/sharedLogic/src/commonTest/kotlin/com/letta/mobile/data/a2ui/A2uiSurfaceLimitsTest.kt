package com.letta.mobile.data.a2ui

import com.letta.mobile.data.model.A2uiComponentId
import com.letta.mobile.data.model.A2uiSurfaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** letta-mobile-by4vm.3: per-surface budgets and the openUrl scheme allowlist. */
class A2uiSurfaceLimitsTest {

    @Test
    fun linkParseAllowsOnlyHttpHttpsAndMailto() {
        assertEquals("https", A2uiLink.parse("https://letta.com/docs")?.scheme)
        assertEquals("http", A2uiLink.parse("http://example.com")?.scheme)
        assertEquals("mailto", A2uiLink.parse("mailto:someone@example.com")?.scheme)
        listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "file:///etc/passwd",
            "intent://scan/#Intent;scheme=zxing;end",
            "content://com.android.contacts/contacts",
            "data:text/html,<script>alert(1)</script>",
            "https://bank.example@evil.example/",
            "https://exa mple.com",
            "mailto:",
            "",
        ).forEach { raw -> assertNull(A2uiLink.parse(raw), raw) }
    }

    @Test
    fun updateOverComponentCapIsRejectedAndSurfaceMarked() {
        val manager = A2uiSurfaceManager()
        val tooMany = A2uiSurfaceLimits.MAX_COMPONENTS_PER_SURFACE + 1

        manager.applyMessage(updateComponents("s1", (0 until tooMany).map { text("t$it") }))

        val surface = assertNotNull(manager.surface(A2uiSurfaceId("s1")))
        val violation = assertIs<A2uiSurfaceLimitViolation.TooManyComponents>(surface.limitViolation)
        assertEquals(tooMany, violation.count)
        assertTrue(surface.components.isEmpty())

        // A later, small update cannot resurrect a rejected surface.
        manager.applyMessage(updateComponents("s1", listOf(text("ok"))))
        assertTrue(manager.surface(A2uiSurfaceId("s1"))!!.components.isEmpty())
    }

    @Test
    fun updatesWithinCapMergeAcrossFrames() {
        val manager = A2uiSurfaceManager()
        val half = A2uiSurfaceLimits.MAX_COMPONENTS_PER_SURFACE / 2

        manager.applyMessage(updateComponents("s1", (0 until half).map { text("a$it") }))
        manager.applyMessage(updateComponents("s1", (0 until half).map { text("b$it") }))

        val surface = assertNotNull(manager.surface(A2uiSurfaceId("s1")))
        assertNull(surface.limitViolation)
        assertEquals(A2uiSurfaceLimits.MAX_COMPONENTS_PER_SURFACE, surface.components.size)
    }

    @Test
    fun budgetStopsGrantingAtTheNodeCapAndReleaseFreesSlots() {
        val budget = A2uiRenderBudget(maxNodes = 3)

        assertEquals(A2uiRenderClaim.Granted, budget.claim(A2uiComponentId("a")))
        assertEquals(A2uiRenderClaim.Granted, budget.claim(A2uiComponentId("b")))
        assertEquals(A2uiRenderClaim.Granted, budget.claim(A2uiComponentId("c")))
        assertEquals(A2uiRenderClaim.DeniedBudget, budget.claim(A2uiComponentId("d")))

        budget.release(A2uiComponentId("b"))
        assertEquals(A2uiRenderClaim.Granted, budget.claim(A2uiComponentId("d")))
    }

    @Test
    fun repeatedComponentIsCappedPerListItemScope() {
        val budget = A2uiRenderBudget()
        val cap = A2uiSurfaceLimits.MAX_INSTANCES_PER_COMPONENT

        repeat(cap) { assertEquals(A2uiRenderClaim.Granted, budget.claim(A2uiComponentId("row"))) }
        assertEquals(A2uiRenderClaim.DeniedRepeat, budget.claim(A2uiComponentId("row")))
        // A list template renders the same component once per data item; each item is its own scope.
        assertEquals(A2uiRenderClaim.Granted, budget.claim(A2uiComponentId("row"), scopeKey = "/items/0"))
    }

    @Test
    fun diamondDagOfDepthTwentyClaimsABoundedNumberOfInstances() {
        // Each level's node lists the next level twice: unbounded, the walk visits 2^21 - 1 instances.
        val depth = 20
        val components = (0..depth).associate { level ->
            val id = "level$level"
            id to if (level == depth) text(id) else column(id, List(2) { "level${level + 1}" })
        }
        val budget = A2uiRenderBudget()
        var attempted = 0

        fun walk(id: String, depthSoFar: Int) {
            attempted++
            if (depthSoFar > A2uiSurfaceLimits.MAX_RENDER_DEPTH) return
            if (budget.claim(A2uiComponentId(id)) != A2uiRenderClaim.Granted) return
            components.getValue(id).children.take(A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER)
                .forEach { walk(it, depthSoFar + 1) }
        }
        walk("level0", 0)

        val perLevel = A2uiSurfaceLimits.MAX_INSTANCES_PER_COMPONENT
        assertTrue(budget.rendered <= (depth + 1) * perLevel, "rendered=${budget.rendered}")
        assertTrue(attempted <= (depth + 1) * perLevel * 2, "attempted=$attempted")
    }

    @Test
    fun longTextIsClampedToTheNodeLimit() {
        val long = "x".repeat(A2uiSurfaceLimits.MAX_TEXT_LENGTH_PER_NODE + 10)

        assertEquals(A2uiSurfaceLimits.MAX_TEXT_LENGTH_PER_NODE, long.clampToA2uiTextLimit().length)
        assertEquals("short", "short".clampToA2uiTextLimit())
    }

    private fun updateComponents(surfaceId: String, components: List<A2uiComponent>) =
        A2uiMessage.UpdateComponents(updateComponents = A2uiUpdateComponentsPayload(surfaceId, components))

    private fun text(id: String) =
        A2uiComponent(id, "Text", JsonObject(mapOf("text" to JsonPrimitive(id))))

    private fun column(id: String, children: List<String>) =
        A2uiComponent(id, "Column", JsonObject(mapOf("children" to JsonArray(children.map(::JsonPrimitive)))))
}
