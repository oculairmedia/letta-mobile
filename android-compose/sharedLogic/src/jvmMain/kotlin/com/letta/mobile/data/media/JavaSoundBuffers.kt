package com.letta.mobile.data.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * The byte work [JavaSoundMediaPlayer] needs around its state machine: fetching a remote file within
 * a cap, measuring a decoded stream and converting it to PCM a Clip can open. Never logs a URL.
 */
internal object JavaSoundBuffers {
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val PCM_BITS = 16
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** The stream's length in milliseconds, or null when the format does not say. */
    fun durationOf(stream: AudioInputStream): Long? {
        val frames = stream.frameLength
        val rate = stream.format.frameRate
        if (frames == AudioSystem.NOT_SPECIFIED.toLong()) return null
        if (rate <= 0f) return null
        return (frames / rate * MILLIS_PER_SECOND).toLong()
    }

    /** A stream a Clip can open: the file's own 16-bit PCM, or a 16-bit signed PCM conversion of it. */
    fun pcmStream(data: ByteArray): AudioInputStream {
        val source = AudioSystem.getAudioInputStream(ByteArrayInputStream(data))
        val format = source.format
        if (isClipPcm(format)) return source
        val target = AudioFormat(format.sampleRate, PCM_BITS, format.channels, true, false)
        return AudioSystem.getAudioInputStream(target, source)
    }

    private fun isClipPcm(format: AudioFormat): Boolean =
        format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleSizeInBits == PCM_BITS

    /** Downloads [url] whole, refusing more than [maxBytes]. */
    fun fetchBounded(url: String, maxBytes: Long): ByteArray {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            return connection.getInputStream().use { readBounded(it, maxBytes) }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    /** Reads [input] whole, refusing more than [maxBytes]. */
    fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val data = input.readNBytes((maxBytes + 1).toInt())
        if (data.size > maxBytes) throw IOException("media exceeds the buffered playback limit")
        return data
    }
}
