package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookPeerGrant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookReferenceGrantTest {
    @Test
    fun editorRequiresBothReadAndEdit() {
        val grant = NotebookPeerGrant(peerId = "peer", mayRead = true, mayEdit = true)
        assertTrue(mayEditNotebookContent(grant))
        assertFalse(mayEditNotebookContent(grant.copy(mayRead = false)))
        assertFalse(mayEditNotebookContent(grant.copy(mayEdit = false)))
        assertFalse(mayEditNotebookContent(null))
    }

    @Test
    fun referenceRequiresBothReadAndReference() {
        val grant = NotebookPeerGrant(peerId = "peer", mayRead = true, mayReference = true)
        assertTrue(mayResolveNotebookReference(grant))
        assertFalse(mayResolveNotebookReference(grant.copy(mayRead = false)))
        assertFalse(mayResolveNotebookReference(grant.copy(mayReference = false)))
        assertFalse(mayResolveNotebookReference(null))
    }
}
