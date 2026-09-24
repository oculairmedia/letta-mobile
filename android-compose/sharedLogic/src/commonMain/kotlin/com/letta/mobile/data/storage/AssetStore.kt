package com.letta.mobile.data.storage

import kotlinx.serialization.Serializable

/**
 * A large payload kept out of whatever refers to it: an image pasted on a canvas, a file dropped
 * on one, a picture in a chat. The bytes live once in an [AssetStore], addressed by their hash;
 * the scene, the op, the message carries only this.
 *
 * [ref] is `sha256:<64 hex>`, so identical bytes are one asset however many times they are placed.
 * [mediaType] (`image/png`, `application/pdf`, ...) says what they are; [byteSize] how many, so a
 * reader can decide whether to fetch them before it has them.
 */
@Serializable
data class AssetRef(
    val ref: String,
    val mediaType: String,
    val byteSize: Long,
)

/**
 * Content-addressed storage for assets of any media type, shared by every surface that keeps large
 * payloads out of its documents (letta-mobile-w3nb2). The generic form of the chat's
 * [ImageBlobStore]: nothing here knows or cares that most assets today are images.
 *
 * Writes are idempotent (the same bytes give the same ref) and a read returns only bytes that
 * still hash to their ref, so a torn or tampered file reads as missing rather than as wrong.
 */
interface AssetStore {
    /** Stores [bytes] and returns their ref; storing the same bytes again returns the same ref. */
    fun put(mediaType: String, bytes: ByteArray): AssetRef

    /** The bytes for [ref], or null when this store does not have them (or they fail their hash). */
    fun get(ref: String): ByteArray?

    /** Whether this store has [ref], without reading it. */
    fun has(ref: String): Boolean

    /** The media type [ref] was stored with, or null when it is not here. */
    fun mediaType(ref: String): String?
}

/** The shape of an asset ref, and the check every store applies before it touches a path with one. */
object AssetRefs {
    const val PREFIX: String = "sha256:"
    private val PATTERN = Regex("sha256:[a-f0-9]{64}")

    fun isValid(ref: String): Boolean = PATTERN.matches(ref)

    /** The hex digest of a valid [ref], or null for anything else. */
    fun hashOf(ref: String): String? = ref.takeIf(::isValid)?.removePrefix(PREFIX)
}
