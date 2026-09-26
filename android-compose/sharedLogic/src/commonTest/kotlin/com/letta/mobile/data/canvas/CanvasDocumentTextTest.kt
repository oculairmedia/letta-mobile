package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasDocumentTextTest {

    @Test
    fun readsEachBlocksTextInOrder() {
        val doc = """{"version":2,"blocks":[
            {"id":"a","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"Plan","spans":[{"style":"bold"}]}},
            {"id":"b","type":{"typeId":"todo"},"content":{"kind":"text","version":1,"text":"the week","spans":[]},
             "children":[{"id":"c","content":{"kind":"text","text":"nested"}}]}
        ]}"""
        assertEquals("Plan\nthe week\nnested", CanvasDocumentText.plainText(doc))
    }

    @Test
    fun aDocumentAsTheEditorSavesItReadsBack() {
        // As stored by the canvas for a shape label.
        val stored = "{\"version\":2,\"blocks\":[{\"id\":\"88541c45\",\"type\":{\"typeId\":\"paragraph\"},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"fdafd\",\"spans\":[]}}]}"
        assertEquals("fdafd", CanvasDocumentText.plainText(stored))
    }

    @Test
    fun emptyOrUnreadableIsEmpty() {
        assertEquals("", CanvasDocumentText.plainText(""))
        assertEquals("", CanvasDocumentText.plainText("not json"))
        assertEquals("", CanvasDocumentText.plainText("""{"version":2,"blocks":[]}"""))
    }
}
