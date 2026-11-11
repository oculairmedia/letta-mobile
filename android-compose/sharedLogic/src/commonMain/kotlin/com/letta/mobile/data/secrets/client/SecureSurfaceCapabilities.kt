package com.letta.mobile.data.secrets.client

/** One protection a platform can put around the secure form while it is shown. */
enum class SecureProtection {
    /** The OS keeps the form's window out of screenshots, screen recordings and the recents thumbnail (Android FLAG_SECURE). */
    SCREEN_CAPTURE_BLOCKED,

    /** The OS marks the form's content as sensitive, so screen sharing and projection hide it (Android 15+). */
    SENSITIVE_CONTENT,

    /** Autofill services are told to ignore the form (Android importantForAutofill). */
    AUTOFILL_EXCLUDED,

    /** The keyboard is asked not to learn from what is typed (Android IME_FLAG_NO_PERSONALIZED_LEARNING). */
    IME_LEARNING_DISABLED,

    /** The field asks for a password keyboard without suggestions or autocorrect. */
    PASSWORD_KEYBOARD,

    /**
     * The text field itself holds only mask characters, so copy, cut, drag, accessibility and the
     * clipboard can never see the value. Every platform has it: it is the shared form's own design.
     */
    MASKED_FIELD_STATE,
}

/**
 * Which [SecureProtection]s were active while a secure form was shown, as the platform binding
 * reports them, so a later stage can tell the person (and the host) honestly what was in place.
 * It never claims a protection the binding could not apply.
 */
data class SecureSurfaceCapabilities(
    /** The protections in place. */
    val active: Set<SecureProtection>,
) {
    /** Whether [protection] was in place. */
    operator fun contains(protection: SecureProtection): Boolean = protection in active

    /** Common values. */
    companion object {
        /** What the shared form guarantees on every platform, with no platform help. */
        val SharedOnly: SecureSurfaceCapabilities = SecureSurfaceCapabilities(setOf(SecureProtection.MASKED_FIELD_STATE))
    }
}
