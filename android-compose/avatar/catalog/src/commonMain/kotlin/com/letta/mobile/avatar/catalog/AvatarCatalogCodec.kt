package com.letta.mobile.avatar.catalog

import com.letta.mobile.avatar.core.AvatarModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown by [AvatarCatalogCodec.decode] for structurally invalid documents. */
class AvatarCatalogException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * `catalog.json` wire format: a versioned wrapper around the entry list so
 * the schema can evolve without breaking older files. Forward-tolerant like
 * the manifest codec (unknown fields ignored, newer majors rejected).
 */
object AvatarCatalogCodec {
    const val SCHEMA_VERSION = 1

    /** Conventional catalog file name inside a catalog directory. */
    const val FILE_NAME = "catalog.json"

    @Serializable
    private data class CatalogDocument(
        val schemaVersion: Int = SCHEMA_VERSION,
        val entries: List<AvatarModel> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun encode(entries: List<AvatarModel>): String =
        json.encodeToString(CatalogDocument(entries = entries))

    fun decode(text: String): List<AvatarModel> {
        val document = try {
            json.decodeFromString<CatalogDocument>(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AvatarCatalogException("Malformed avatar catalog: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AvatarCatalogException("Malformed avatar catalog: ${e.message}", e)
        }
        if (document.schemaVersion > SCHEMA_VERSION) {
            throw AvatarCatalogException(
                "Catalog schema version ${document.schemaVersion} is newer than " +
                    "supported version $SCHEMA_VERSION",
            )
        }
        return document.entries
    }
}
