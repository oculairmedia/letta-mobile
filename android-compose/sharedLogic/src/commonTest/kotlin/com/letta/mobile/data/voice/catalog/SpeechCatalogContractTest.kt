package com.letta.mobile.data.voice.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The contract for listing any OpenAI-compatible speech server: every model and voice listing shape
 * servers answer with, the split into transcription and speech models, the voice fallback, and the
 * saved name that is always offered.
 */
class SpeechCatalogContractTest {
    @Test
    fun modelListingsReadInEveryShape() {
        val expected = listOf("whisper-1", "tts-1")
        listOf(
            """{"object":"list","data":[{"id":"whisper-1","object":"model"},{"id":"tts-1","object":"model"}]}""",
            """{"models":[{"id":"whisper-1"},{"id":"tts-1"}]}""",
            """{"models":["whisper-1","tts-1"]}""",
            """["whisper-1","tts-1"]""",
            """[{"name":"whisper-1"},{"name":"tts-1"}]""",
        ).forEach { body -> assertEquals(expected, SpeechListing.modelIds(body), body) }
    }

    @Test
    fun voiceListingsReadInEveryShape() {
        val expected = listOf("a", "b")
        listOf(
            """{"voices":["a","b"]}""",
            """{"voices":[{"id":"a"},{"id":"b"}]}""",
            """{"voices":[{"name":"a"},{"name":"b"}]}""",
            """{"voices":[{"voice_id":"a"},{"voice_id":"b"}]}""",
            """{"voices":[{"id":"a","name":"Alpha"},{"id":"b","name":"Beta"}]}""",
            """{"data":[{"id":"a"},{"id":"b"}]}""",
            """["a","b"]""",
            """[{"id":"a"},{"name":"b"}]""",
        ).forEach { body -> assertEquals(expected, SpeechListing.voiceIds(body), body) }
    }

    @Test
    fun aBodyThatIsNotAListingReadsAsNoneRatherThanPartly() {
        listOf(
            "not json",
            "<html>Not Found</html>",
            """{"detail":"Not Found"}""",
            """{"voices":"a"}""",
            """{"voices":[{"id":"a"},{"label":"b"}]}""",
            """{"voices":["a",""]}""",
            """{"voices":[1,2]}""",
            """{"voices":[]}""",
        ).forEach { body -> assertNull(SpeechListing.voiceIds(body), body) }
        assertNull(SpeechListing.modelIds("""{"error":{"message":"bad key"}}"""))
    }

    @Test
    fun modelsSplitIntoTranscriptionAndSpeechByTheirIds() {
        val models = listOf("whisper-1", "gpt-4o-transcribe", "tts-1", "gpt-4o-mini-tts", "kokoro-speech", "gpt-4o")
        assertEquals(listOf("whisper-1", "gpt-4o-transcribe"), SpeechChoices.transcriptionModels(models, null))
        assertEquals(listOf("tts-1", "gpt-4o-mini-tts", "kokoro-speech"), SpeechChoices.speechModels(models, null))
        assertEquals(listOf("Systran/Faster-Whisper-Small"), SpeechChoices.transcriptionModels(listOf("Systran/Faster-Whisper-Small"), ""))
    }

    @Test
    fun aSideTheSplitLeavesEmptyOffersEveryId() {
        val models = listOf("kokoro", "parakeet")
        assertEquals(models, SpeechChoices.transcriptionModels(models, null))
        assertEquals(models, SpeechChoices.speechModels(models, null))
        val onlyWhisper = listOf("whisper-1")
        assertEquals(onlyWhisper, SpeechChoices.speechModels(onlyWhisper, null))
    }

    @Test
    fun theSavedNameIsAlwaysOfferedFirstWhenTheServerDoesNotListIt() {
        assertEquals(listOf("my-stt", "whisper-1"), SpeechChoices.transcriptionModels(listOf("whisper-1", "tts-1"), "my-stt"))
        assertEquals(listOf("tts-1"), SpeechChoices.speechModels(listOf("whisper-1", "tts-1"), " tts-1 "))
        assertEquals(listOf("clone", "a", "b"), SpeechChoices.voices(listOf("a", "b"), "clone"))
        assertEquals(listOf("only-mine"), SpeechChoices.speechModels(emptyList(), "only-mine"))
    }

    @Test
    fun voicesFallBackToOpenAisDocumentedVoices() {
        val documented = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        assertEquals(documented, SpeechChoices.OPENAI_VOICES)
        assertEquals(documented, SpeechChoices.voices(null, null))
        assertEquals(documented, SpeechChoices.voices(listOf(" ", ""), "alloy"))
        assertEquals(listOf("af_heart") + documented, SpeechChoices.voices(null, "af_heart"))
    }

    @Test
    fun repeatedAndBlankIdsAreOfferedOnce() {
        assertEquals(listOf("tts-1"), SpeechChoices.speechModels(listOf("tts-1", " tts-1", ""), null))
    }
}
