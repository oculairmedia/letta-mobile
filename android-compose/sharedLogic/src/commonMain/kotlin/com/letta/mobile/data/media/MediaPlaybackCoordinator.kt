package com.letta.mobile.data.media

/**
 * Keeps one item playing at a time across a page. Renderers start playback through [play], so
 * starting one pauses whichever played before; voice mode calls [pauseAll] before it speaks. It owns
 * no player: a renderer still releases its own and calls [forget] when it does.
 */
class MediaPlaybackCoordinator {
    private var active: MediaPlayer? = null

    /** The player now allowed to sound, if any. */
    val activePlayer: MediaPlayer? get() = active

    /** Pause the previous item, then play [player]. */
    fun play(player: MediaPlayer) {
        val previous = active
        if (previous != null && previous !== player) previous.pause()
        active = player
        player.play()
    }

    /** Pause whatever is playing, keeping its position (voice mode speaking, the page stopping). */
    fun pauseAll() {
        active?.pause()
    }

    /** [player] is being released; stop tracking it. */
    fun forget(player: MediaPlayer) {
        if (active === player) active = null
    }
}
