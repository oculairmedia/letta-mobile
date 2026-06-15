package com.letta.mobile.data.storage

/**
 * Content-addressed blob store for image attachments stripped from conversation
 * context (letta-mobile-xybm2).
 *
 * Images are persisted BEFORE the stripper removes their inline base64 from
 * messages.jsonl, allowing timeline rehydration to resolve image_ref pointers
 * back to displayable bytes without re-parsing the model context.
 *
 * The ref format is "sha256:<hex>" — stable, content-addressed, deduplicates
 * identical images across messages. The store is conversation-scoped; each
 * conversation's blobs live in a sidecar directory next to messages.jsonl.
 */
interface ImageBlobStore {
    /**
     * Persist image bytes to the blob store and return a resolvable ref.
     * The returned ref is a content-addressed pointer (e.g. "sha256:<hex>")
     * that can be embedded in an image_ref part and later resolved via
     * [getBytes].
     *
     * @param mediaType the MIME type (e.g. "image/png", "image/jpeg")
     * @param bytes the raw image bytes
     * @return a ref string ("sha256:<hex>") that uniquely identifies this blob
     */
    fun putBytes(mediaType: String, bytes: ByteArray): String

    /**
     * Resolve an image_ref pointer to its raw bytes.
     *
     * @param ref a content-addressed ref returned by [putBytes]
     * @return the original image bytes, or null if the blob is missing
     */
    fun getBytes(ref: String): ByteArray?

    /**
     * Check whether a blob exists in the store without loading it.
     *
     * @param ref a content-addressed ref
     * @return true if the blob exists, false otherwise
     */
    fun has(ref: String): Boolean
}
