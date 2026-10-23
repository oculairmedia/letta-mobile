package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.names.CanvasToolNames
import com.letta.mobile.data.chat.tools.steps.ToolActivity
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolFailure
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.steps.activity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** letta-mobile-2ccpv: a canvas call reads as the canvas op it ran, previews it, and opens the canvas. */
class CanvasToolPresenterTest {
    private val registry = BundledToolPresenters.registry

    private fun call(name: String, arguments: String, result: String? = """{"ok":true}""") = ToolCallFacts(name, arguments, result)

    @Test
    fun aComposeReadsAsTheComposedTitleAndPreviewsItsItemsInOrder() {
        val facts = call(
            "canvas_compose",
            """{"title":"Sprint plan","items":[{"kind":"NOTE","markdown":"Ship it\nsoon"},""" +
                """{"kind":"GROUP","label":"Risks","children":[{"kind":"CARD","title":"Auth"}]}]}""",
        )
        val line = registry.line(facts)
        assertEquals(ToolVerb.ComposedCanvas, line.verb)
        assertEquals("Sprint plan", line.target)
        assertEquals(listOf<ToolPreviewTarget>(ToolPreviewTarget.Canvas), line.previews)
        assertNull(line.failure)
        assertEquals(ToolInset.Text("NOTE  Ship it\nGROUP  Risks\n  CARD  Auth"), registry.inset(facts))
    }

    @Test
    fun anOpsBatchReadsAsItsOpTypesAndPreviewsEachOp() {
        val facts = call(
            "canvas_apply_ops",
            """{"ops":[{"type":"add_element","elementId":"box-1"},{"type":"add_element","elementId":"box-2"},""" +
                """{"type":"connect","id":"a1","from":"n1","to":"n2"}]}""",
        )
        val line = registry.line(facts)
        assertEquals(ToolVerb.EditedCanvas, line.verb)
        assertEquals("add_element ×2, connect", line.target)
        assertEquals(ToolInset.Text("add_element  box-1\nadd_element  box-2\nconnect  a1"), registry.inset(facts))
    }

    @Test
    fun aReadNamesTheCliOpAndAListingOpensNoCanvas() {
        val layout = registry.line(call("canvas_get_layout", "{}"))
        assertEquals(ToolVerb.ReadCanvas, layout.verb)
        assertEquals("layout", layout.target)
        assertEquals(listOf<ToolPreviewTarget>(ToolPreviewTarget.Canvas), layout.previews)
        assertEquals(emptyList(), registry.line(call("canvas_list", "{}")).previews)
        assertIs<ToolInset.Arguments>(registry.inset(call("canvas_get_scene", """{"canvas_id":"c1"}""")))
    }

    @Test
    fun aRefusalIsAFailure() {
        val refused = call("canvas_compose", """{"items":[]}""", result = """{"ok": false, "code": "invalid"}""")
        assertEquals(ToolFailure(null), registry.line(refused).failure)
        assertEquals(ToolFailure(null), registry.line(call("canvas_create", "{}", result = """{"error":"denied"}""")).failure)
    }

    @Test
    fun everyCanvasToolIsClaimedAndTheNamesAreTheContractsOwn() {
        assertEquals(CanvasToolContract.COMPOSE, CanvasToolNames.COMPOSE)
        CanvasToolNames.ALL.forEach { name ->
            assertEquals(CanvasToolPresenter.id, registry.presenterFor(call(name, "{}")).id, name)
            assertEquals(ToolActivity.Drew, registry.line(call(name, "{}")).verb.activity, name)
        }
    }
}
