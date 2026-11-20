package com.letta.mobile.data.media

/**
 * A [MediaEngine] whose readings the test sets: it opens instantly with [durationMs] unless
 * [loadsImmediately] is false, plays only when told, and records the commands it received.
 */
class ScriptedMediaEngine(
    private val durationMs: Long = 10_000L,
    private val loadsImmediately: Boolean = true,
) : MediaEngine {
    var reading = MediaEngineReading()
    val commands = mutableListOf<String>()
    var outputVolume = 1f
        private set

    /** When set, seeks are recorded but the position stays put, as an engine still seeking reports it. */
    var lagsOnSeek = false

    override fun open(source: MediaSource) {
        commands += "open"
        reading = if (loadsImmediately) loaded() else MediaEngineReading(isLoading = true)
    }

    override fun play() {
        commands += "play"
        reading = reading.copy(isPlaying = true, atEnd = false)
    }

    override fun pause() {
        commands += "pause"
        reading = reading.copy(isPlaying = false)
    }

    override fun seekTo(positionMs: Long) {
        commands += "seek:$positionMs"
        reading = reading.copy(positionMs = if (lagsOnSeek) reading.positionMs else positionMs, atEnd = false)
    }

    override fun setOutputVolume(volume: Float) {
        outputVolume = volume
    }

    override fun read(): MediaEngineReading = reading

    override fun dispose() {
        commands += "dispose"
    }

    /** Finishes a slow open. */
    fun finishLoading() {
        reading = loaded()
    }

    /** Plays through to the end, as the engine's end callback would report it. */
    fun reachEnd() {
        reading = reading.copy(isPlaying = false, positionMs = durationMs, atEnd = true)
    }

    private fun loaded() = MediaEngineReading(hasMedia = true, durationMs = durationMs)
}
