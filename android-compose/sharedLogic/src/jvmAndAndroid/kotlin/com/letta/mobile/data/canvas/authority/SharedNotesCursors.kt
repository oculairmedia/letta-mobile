package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.workspace.SharedNotesCursor
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The host's encoding of where a `notebook.shared.list` page stopped: `v1.` and then unpadded
 * base64url of an 8-byte tag of the principal followed by the UTF-8 id of the last grant that page
 * checked. It names a position in the caller's own grant list (ordered by document id, see
 * [NotebookGrantStore.documentsOf]), so it carries nothing about any other principal.
 *
 * The tag is neither a secret nor a MAC: it only makes a cursor issued to another principal, or in
 * another format, refused rather than read. A cursor a caller forges for itself can only choose a
 * starting point in its own list, which every page still rechecks grant by grant. Grant document ids
 * are treated as not secret from their grantee, so a cursor carries one of the caller's own grant ids
 * readably rather than sealed.
 */
internal object SharedNotesCursors {
    /** The longest document id (in UTF-8 bytes) a cursor can name; a longer grant id is never a listed position. */
    const val MAX_ID_BYTES = 256

    /** Whether [id] fits in a cursor; ids the host issues for notes are far shorter. */
    fun positionable(id: NotebookDocumentId): Boolean = id.value.encodeToByteArray().size <= MAX_ID_BYTES

    /** The cursor naming the position just after [last] in [principal]'s own ordered grant list. */
    fun issue(principal: String, last: NotebookDocumentId): SharedNotesCursor {
        require(positionable(last)) { "A cursor cannot name this document id" }
        return SharedNotesCursor(PREFIX + ENCODER.encodeToString(tag(principal) + last.value.encodeToByteArray()))
    }

    /** The position [cursor] names for [principal]; null when it is malformed, too long, or issued to anyone else. */
    fun read(principal: String, cursor: SharedNotesCursor): NotebookDocumentId? {
        val bytes = payload(principal, cursor.value) ?: return null
        val id = utf8(bytes) ?: return null
        if (id.isBlank()) return null
        return NotebookDocumentId(id)
    }

    /** The id bytes after a matching tag, from a canonical cursor of the current format. */
    private fun payload(principal: String, text: String): ByteArray? {
        val body = text.takeIf(::wellFormed)?.removePrefix(PREFIX) ?: return null
        val bytes = canonical(body)?.takeIf { it.size > TAG_BYTES } ?: return null
        if (!MessageDigest.isEqual(bytes.copyOfRange(0, TAG_BYTES), tag(principal))) return null
        return bytes.copyOfRange(TAG_BYTES, bytes.size)
    }

    /** Within the length both sides accept, and of the current format. */
    private fun wellFormed(text: String): Boolean {
        if (SharedNotesWire.cursorOf(text) == null) return false
        return text.startsWith(PREFIX)
    }

    /** Decoded bytes only when [body] is exactly how this host writes them (no padding, no alternative spellings). */
    private fun canonical(body: String): ByteArray? {
        val bytes = runCatching { DECODER.decode(body) }.getOrNull() ?: return null
        return bytes.takeIf { ENCODER.encodeToString(it) == body }
    }

    /** [bytes] as strict UTF-8; null when they are not valid UTF-8. */
    private fun utf8(bytes: ByteArray): String? =
        runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()

    private fun tag(principal: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((DOMAIN + principal).encodeToByteArray()).copyOf(TAG_BYTES)

    private const val PREFIX = "v1."
    private const val TAG_BYTES = 8
    private const val DOMAIN = "letta.notebook.shared.list.cursor\u0000"
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
}
