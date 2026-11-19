package com.letta.mobile.data.voice.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the model and voice listings speech servers answer with, in the shapes they use:
 *
 * - models (`GET /v1/models`): OpenAI's `{"data":[{"id":"…"}]}`, or the ids under `models`, or a
 *   top-level array;
 * - voices (`GET /v1/audio/voices`, an extension): `{"voices":["a","b"]}`,
 *   `{"voices":[{"id":"a"}]}`, the ids under `data`, or a top-level array.
 *
 * Each entry is a string, or an object naming itself by `id`, else `name`, else `voice_id`. A body
 * that is not such a listing (not JSON, no list, an entry without a name) reads as null, never as a
 * partial list. Only the named fields are read; nothing is kept or logged.
 */
internal object SpeechListing {
    private val ID_FIELDS = listOf("id", "name", "voice_id")
    private val MODEL_FIELDS = listOf("data", "models")
    private val VOICE_FIELDS = listOf("voices", "data")

    /** The model ids in [body], or null when it is not a model listing. */
    fun modelIds(body: String): List<String>? = ids(parse(body), MODEL_FIELDS)

    /** The voice ids in [body], or null when it is not a voice listing or lists none. */
    fun voiceIds(body: String): List<String>? = ids(parse(body), VOICE_FIELDS)?.takeIf { it.isNotEmpty() }

    /**
     * The ids in a listing: a top-level array, or the array under the first of [fields] present;
     * null when [root] is not such a listing.
     */
    fun ids(root: JsonElement?, fields: List<String>): List<String>? {
        val list = root as? JsonArray ?: (root as? JsonObject)?.let { obj -> fields.firstNotNullOfOrNull { obj[it] as? JsonArray } }
        return list?.map { entry -> idOf(entry) ?: return null }
    }

    private fun idOf(entry: JsonElement): String? {
        val primitive = (entry as? JsonPrimitive) ?: (entry as? JsonObject)?.let { obj ->
            ID_FIELDS.firstNotNullOfOrNull { obj[it] as? JsonPrimitive }
        }
        return primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    private fun parse(body: String): JsonElement? = runCatching { Json.parseToJsonElement(body) }.getOrNull()
}
