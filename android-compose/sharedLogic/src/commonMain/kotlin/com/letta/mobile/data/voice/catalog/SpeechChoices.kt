package com.letta.mobile.data.voice.catalog

/**
 * The names a picker offers from a [SpeechCatalog], for any OpenAI-compatible server.
 *
 * Models are split by id: transcription models contain `whisper` or `transcribe`, speech models
 * contain `tts` or `speech` (any case). A side the split leaves empty offers every id, since an
 * unknown server's names say nothing. Voices are the server's own where it lists them, else OpenAI's
 * documented voices. The [selected] name is always offered (first, when the server does not list
 * it), so a saved name never disappears from its picker.
 */
object SpeechChoices {
    /** OpenAI's documented voices, offered when a server lists none. */
    val OPENAI_VOICES: List<String> = listOf(
        "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse",
    )

    private val TRANSCRIPTION_MARKERS = listOf("whisper", "transcribe")
    private val SPEECH_MARKERS = listOf("tts", "speech")

    /** The transcription models to offer from [models], with [selected]. */
    fun transcriptionModels(models: List<String>, selected: String?): List<String> = side(models, TRANSCRIPTION_MARKERS, selected)

    /** The speech models to offer from [models], with [selected]. */
    fun speechModels(models: List<String>, selected: String?): List<String> = side(models, SPEECH_MARKERS, selected)

    /** The voices to offer: [listed] when the server lists any, else [OPENAI_VOICES]; with [selected]. */
    fun voices(listed: List<String>?, selected: String?): List<String> =
        withSelected(listed?.let(::clean)?.takeIf { it.isNotEmpty() } ?: OPENAI_VOICES, selected)

    private fun side(models: List<String>, markers: List<String>, selected: String?): List<String> {
        val ids = clean(models)
        val matched = ids.filter { id -> markers.any { id.contains(it, ignoreCase = true) } }
        return withSelected(matched.ifEmpty { ids }, selected)
    }

    private fun clean(ids: List<String>): List<String> = ids.map(String::trim).filter(String::isNotEmpty).distinct()

    private fun withSelected(offered: List<String>, selected: String?): List<String> {
        val chosen = selected?.trim()?.takeIf { it.isNotEmpty() } ?: return offered
        return if (chosen in offered) offered else listOf(chosen) + offered
    }
}
