package com.letta.mobile.ui.ambient

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf

/** Whose voice the ambient glow follows while voice mode is on. */
enum class AmbientVoiceRole {
    /** The person: the microphone is open (listening) or they are speaking. Drawn in its own colour. */
    You,

    /** The agent: its reply is being spoken. Drawn in the thinking glow's own colour. */
    Agent,
}

/**
 * Voice mode's drive for the ambient glow, while it follows a voice rather than the run: whose
 * voice ([role]), the colour the person's side is drawn in ([youTintArgb], an ARGB colour from the
 * theme), and the voice's smoothed [level] (0..1, read at draw time, never in composition).
 *
 * A glow given none (the default) draws exactly as it always has. Given one it shows as a running
 * glow in the role's colour whose strength follows the level: [envelope] scales the shader's
 * intensity and [streamEnergy] lifts its stream energy, the two inputs the shader already takes, so
 * the shader source itself is unchanged.
 */
@Stable
class AmbientVoice(
    /** Whose voice it is. */
    val role: AmbientVoiceRole,
    /** The person's colour, as ARGB; the agent's side keeps the glow's own running tint. */
    val youTintArgb: Int,
    /** The voice's smoothed level, 0..1; call it at draw time. */
    val level: () -> Float,
) {
    /** The glow's intensity [base] scaled by the level: a resting share at silence, all of it at full level. */
    fun envelope(base: Float): Float = base * (RESTING_SHARE + (1f - RESTING_SHARE) * currentLevel())

    /** The glow's stream energy [base], lifted to at least the level. */
    fun streamEnergy(base: Float): Float = maxOf(base, currentLevel())

    private fun currentLevel(): Float {
        val value = level()
        return if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
    }

    /** The drive's constants. */
    companion object {
        /** The share of its intensity the glow keeps at silence (listening, nobody speaking). */
        const val RESTING_SHARE: Float = 0.35f
    }
}

/**
 * Voice mode's drive for the chat page's ambient glows, or null while the glow follows the run (voice
 * mode off, waiting for the reply, a failure). Provided by ChatSurface; the docked panel's glow and the
 * hosts' full-page glows (composed inside the page) read it, as they read [LocalThinkingGlowStyle].
 */
val LocalAmbientVoice: ProvidableCompositionLocal<AmbientVoice?> = compositionLocalOf { null }
