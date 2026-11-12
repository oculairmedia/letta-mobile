package com.letta.mobile.data.voice

/** What a page can offer the person when voice fails. */
enum class VoiceUserAction {
    /** Offer to try again. */
    RETRY,

    /** Offer to open the voice settings (pick another engine, grant a permission, fix a key). */
    OPEN_SETTINGS,

    /** Nothing to offer; the failure was expected or already resolved. */
    NONE,
}

/** The voice part a failure came from. */
enum class VoicePort {
    /** The hearing engine. */
    EAR,

    /** The speaking engine. */
    MOUTH,

    /** Whoever answers the utterance. */
    RESPONDER,

    /** The wake-word or activation helper. */
    ACTIVATION,

    /** The loop itself (an engine that would not let go of its resources). */
    LOOP,
}

/**
 * Stable failure codes shared by every voice port. Codes are fixed so logs, telemetry and pages
 * can switch on them; each carries the [action] a page should offer.
 */
enum class VoiceErrorCode(
    /** The action a page should offer for this code. */
    val action: VoiceUserAction,
) {
    /** The engine is not available on this device or build. */
    UNAVAILABLE(VoiceUserAction.OPEN_SETTINGS),

    /** The engine does not support the configured language. */
    UNSUPPORTED_LOCALE(VoiceUserAction.OPEN_SETTINGS),

    /** The engine could not be prepared (model load, audio device setup). */
    PREPARATION_FAILED(VoiceUserAction.RETRY),

    /** Speech could not be turned into text. */
    TRANSCRIPTION_FAILED(VoiceUserAction.RETRY),

    /** Text could not be turned into speech. */
    SYNTHESIS_FAILED(VoiceUserAction.RETRY),

    /** Synthesised audio could not be played. */
    PLAYBACK_FAILED(VoiceUserAction.RETRY),

    /** The work was cancelled on purpose. */
    CANCELLED(VoiceUserAction.NONE),

    /** An OS permission or a destination grant is missing. */
    PERMISSION_REQUIRED(VoiceUserAction.OPEN_SETTINGS),

    /** The remote endpoint could not be reached. */
    UNREACHABLE(VoiceUserAction.RETRY),

    /** The remote endpoint refused the credentials. */
    UNAUTHORIZED(VoiceUserAction.OPEN_SETTINGS),

    /** The remote endpoint refused the request (unknown model or voice, bad input). */
    REJECTED(VoiceUserAction.OPEN_SETTINGS),

    /** The remote endpoint answered with something that is not the expected shape. */
    BAD_RESPONSE(VoiceUserAction.RETRY),

    /** The request or the reply took longer than its budget. */
    TIMED_OUT(VoiceUserAction.RETRY),

    /** An engine did not release its resources within its budget after being told to stop. */
    UNRESPONSIVE(VoiceUserAction.RETRY),

    /** The utterance never reached the agent. */
    NOT_DELIVERED(VoiceUserAction.RETRY),

    /**
     * The utterance reached the conversation, but no answer could be tied to it with certainty, so
     * nothing was spoken. This happens when the chat session does not (yet) publish which run the
     * sent message started, or when more than one new message could be the utterance. The reply is
     * still in the conversation; only speaking it was skipped.
     */
    UNATTRIBUTED(VoiceUserAction.NONE),

    /** The agent's run failed. */
    RUN_FAILED(VoiceUserAction.RETRY),

    /** An optional asset (wake-word or voice-activity model) is missing; voice keeps working without it. */
    ASSET_MISSING(VoiceUserAction.NONE),

    /** The endpoint address has a query, fragment or user info; it must be a plain base URL. */
    INVALID_ENDPOINT(VoiceUserAction.OPEN_SETTINGS),

    /** A bearer key would travel over an unencrypted connection (plain http to a non-loopback host); nothing was sent. */
    INSECURE_TRANSPORT(VoiceUserAction.OPEN_SETTINGS),

    /** The engine is already in use (a second concurrent listener); the running one is unaffected. */
    BUSY(VoiceUserAction.NONE),

    /** The capture offered for a retry is no longer held by the ear. */
    CAPTURE_EXPIRED(VoiceUserAction.NONE),

    /** An audio source produces a format the engine does not accept; nothing resamples it. */
    UNSUPPORTED_FORMAT(VoiceUserAction.NONE),

    /** Any other engine failure. */
    ENGINE_FAILED(VoiceUserAction.RETRY),
}

/** A tagged voice failure: a fixed [code] and the [port] it came from. Carries no text, audio or address. */
data class VoiceError(
    /** What went wrong. */
    val code: VoiceErrorCode,
    /** Which part failed. */
    val port: VoicePort,
) {
    /** The action a page should offer. */
    val action: VoiceUserAction get() = code.action
}
