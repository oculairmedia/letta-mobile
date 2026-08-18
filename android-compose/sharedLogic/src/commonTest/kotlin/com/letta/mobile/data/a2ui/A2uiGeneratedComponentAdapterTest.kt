package com.letta.mobile.data.a2ui

import com.letta.mobile.data.model.UiGeneratedComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class A2uiGeneratedComponentAdapterTest {

    @Test
    fun `known widget name with object props builds a renderable single-node surface`() {
        val generatedUi = UiGeneratedComponent(
            name = "Card",
            propsJson = """{"cornerRadius":"16"}""",
            fallbackText = "A card",
        )

        val surface = generatedUi.toA2uiSurfaceStateOrNull()

        assertEquals("root", surface?.rootComponentId)
        assertEquals("Card", surface?.components?.get("root")?.component)
        assertEquals("16", surface?.components?.get("root")?.raw?.get("cornerRadius")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        })
    }

    @Test
    fun `unrecognized widget name falls back to null so callers show fallbackText`() {
        // Desktop's preview/demo conversations (DesktopChatModels.kt) use
        // arbitrary human-readable names like "DesktopReadinessCard" that are
        // not A2UI Basic-catalog widget ids.
        val generatedUi = UiGeneratedComponent(
            name = "DesktopReadinessCard",
            propsJson = """{"catalog":"basic","status":"preview"}""",
            fallbackText = "Shared render model is available.",
        )

        assertNull(generatedUi.toA2uiSurfaceStateOrNull())
    }

    @Test
    fun `unparseable props json falls back to null`() {
        val generatedUi = UiGeneratedComponent(
            name = "Card",
            propsJson = "not json",
            fallbackText = "A card",
        )

        assertNull(generatedUi.toA2uiSurfaceStateOrNull())
    }

    @Test
    fun `non-object props json falls back to null`() {
        val generatedUi = UiGeneratedComponent(
            name = "Card",
            propsJson = """["not", "an", "object"]""",
            fallbackText = "A card",
        )

        assertNull(generatedUi.toA2uiSurfaceStateOrNull())
    }
}
