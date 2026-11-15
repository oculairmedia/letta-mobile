package com.letta.mobile.data.voice.host

/** Names, capabilities and limits of the voice-endpoint v1 contract, shared by host and client. */
object HostVoiceWire {
    /** The contract version every setup status and setup write carries. */
    const val CONTRACT_VERSION: Int = 1

    /** Advertised by a host that serves every v1 method; without it a client keeps its own setup. */
    const val CAPABILITY: String = "voice_endpoint_v1"

    /** Reads the shared setup. */
    const val SETUP_GET: String = "voice.setup.get"

    /** Writes the shared setup. */
    const val SETUP_SET: String = "voice.setup.set"

    /** Removes the shared setup. */
    const val SETUP_CLEAR: String = "voice.setup.clear"

    /** Checks whether a speech server answers. */
    const val SETUP_TEST: String = "voice.setup.test"

    /** Lists the stored speech server's models and voices. */
    const val MODELS_LIST: String = "voice.models.list"

    /** Transcribes one clip through the host. */
    const val TRANSCRIBE: String = "voice.transcribe"

    /** Synthesises one text through the host, streamed; only on a request stream of its own. */
    const val SPEECH: String = "voice.speech"

    /** Every v1 method. */
    val METHODS: Set<String> = setOf(SETUP_GET, SETUP_SET, SETUP_CLEAR, SETUP_TEST, MODELS_LIST, TRANSCRIBE, SPEECH)

    /** Frame type of one streamed speech event, sent before the final response. */
    const val STREAM_FRAME_TYPE: String = "admin_rpc_stream"

    /** Frame type a client sends on a speech stream to cancel it. */
    const val CANCEL_FRAME_TYPE: String = "admin_rpc_cancel"

    /** Largest clip accepted for transcription, in bytes before encoding. */
    const val MAX_TRANSCRIPTION_AUDIO_BYTES: Int = 1_048_576

    /** Longest clip accepted for transcription, in milliseconds. */
    const val MAX_TRANSCRIPTION_AUDIO_MS: Long = 30_000L

    /** Longest text accepted for synthesis, in characters. */
    const val MAX_SPEECH_TEXT_CHARS: Int = 4_096

    /** Most PCM bytes one streamed audio event carries, before encoding. */
    const val MAX_STREAM_AUDIO_BYTES: Int = 65_536
}
