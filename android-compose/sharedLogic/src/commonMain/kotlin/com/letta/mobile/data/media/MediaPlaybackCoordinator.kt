package com.letta.mobile.data.media

/**
 * Keeps one item playing at a time across a page. Renderers start playback through [play], so
 * starting one pauses whichever played before; voice mode [hold]s playback while it hears or speaks.
 * It owns no player: a renderer still releases its own and calls [forget] when it does.
 */
class MediaPlaybackCoordinator {
    private var active: MediaPlayer? = null

    /** True while something else owns the speaker (voice mode): [play] does nothing. */
    var held: Boolean = false
        private set

    /** The player now allowed to sound, if any. */
    val activePlayer: MediaPlayer? get() = active

    /** Pause the previous item, then play [player]; nothing while [held]. */
    fun play(player: MediaPlayer) {
        if (held) return
        val previous = active
        if (previous != null && previous !== player) previous.pause()
        active = player
        player.play()
    }

    /** Pause whatever is playing, keeping its position (voice mode speaking, the page stopping). */
    fun pauseAll() {
        active?.pause()
    }

    /** Hold playback (pausing what plays) while [hold] is true; release the hold when false. */
    fun hold(hold: Boolean) {
        held = hold
        if (hold) pauseAll()
    }

    /** [player] is being released; stop tracking it. */
    fun forget(player: MediaPlayer) {
        if (active === player) active = null
    }
}
