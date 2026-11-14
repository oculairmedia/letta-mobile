package com.letta.mobile.data.voice.audio.desktop

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.device.AudioDeviceSelection
import com.letta.mobile.data.voice.audio.line.PcmInputDevice
import com.letta.mobile.data.voice.audio.line.PcmInputLine
import com.letta.mobile.data.voice.audio.line.PcmOutputDevice
import com.letta.mobile.data.voice.audio.line.PcmOutputLine
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Java Sound's description of 16-bit signed little-endian PCM. */
internal fun pcm16(sampleRateHz: Int, channels: Int): AudioFormat =
    AudioFormat(sampleRateHz.toFloat(), BITS_PER_SAMPLE, channels, true, false)

private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SAMPLE = 2
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8

/** Maps Java Sound's exceptions to fixed codes; never keeps their messages (they can name devices). */
internal fun <T> javaSound(block: () -> T): T {
    val outcome = runCatching(block)
    val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
    throw when (error) {
        is VoiceAudioException -> error
        is IllegalArgumentException -> VoiceAudioException(VoiceErrorCode.UNSUPPORTED_FORMAT)
        is LineUnavailableException -> VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        is SecurityException -> VoiceAudioException(VoiceErrorCode.PERMISSION_REQUIRED)
        else -> VoiceAudioException(VoiceErrorCode.ENGINE_FAILED)
    }
}

/** The line for [info] on the selected mixer, or the system default line. */
internal fun lineFor(selection: AudioDeviceSelection, info: DataLine.Info): Line = when (selection) {
    AudioDeviceSelection.SystemDefault -> AudioSystem.getLine(info)
    is AudioDeviceSelection.Specific -> {
        val mixer = AudioSystem.getMixerInfo().firstOrNull { it.name == selection.id.value }
            ?: throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        AudioSystem.getMixer(mixer).getLine(info)
    }
}

/** Microphone lines on `TargetDataLine`, 16-bit little-endian in exactly the asked format. */
internal class JavaSoundInputDevice(private val selection: AudioDeviceSelection) : PcmInputDevice {
    override fun open(format: AudioFrameFormat): PcmInputLine = javaSound {
        val audioFormat = pcm16(format.sampleRateHz, format.channels)
        val line = lineFor(selection, DataLine.Info(TargetDataLine::class.java, audioFormat)) as TargetDataLine
        line.open(audioFormat, format.samplesPerFrame * BYTES_PER_SAMPLE * BUFFERED_FRAMES)
        line.start()
        JavaSoundInputLine(line)
    }

    private companion object {
        const val BUFFERED_FRAMES = 8
    }
}

private class JavaSoundInputLine(private val line: TargetDataLine) : PcmInputLine {
    private var bytes = ByteArray(0)

    override fun read(buffer: ShortArray): Int {
        if (!line.isOpen) throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        if (bytes.size != buffer.size * BYTES_PER_SAMPLE) bytes = ByteArray(buffer.size * BYTES_PER_SAMPLE)
        val read = javaSound { line.read(bytes, 0, bytes.size) } / BYTES_PER_SAMPLE
        for (index in 0 until read) {
            val low = bytes[index * BYTES_PER_SAMPLE].toInt() and BYTE_MASK
            buffer[index] = (low or (bytes[index * BYTES_PER_SAMPLE + 1].toInt() shl BYTE_BITS)).toShort()
        }
        return read
    }

    override fun stop() {
        line.stop()
        line.flush()
    }

    override fun release() {
        line.close()
    }
}

/**
 * Speaker lines on `SourceDataLine`. Opens at the asked rate when the device accepts it, otherwise
 * at [fallbackRateHz] (the player resamples).
 */
internal class JavaSoundOutputDevice(
    private val selection: AudioDeviceSelection,
    private val fallbackRateHz: Int = FALLBACK_RATE_HZ,
) : PcmOutputDevice {
    override fun open(format: PcmFormat): PcmOutputLine {
        val asked = runCatching { openAt(format.sampleRateHz) }
        val failure = asked.exceptionOrNull() as? VoiceAudioException
        if (failure?.code != VoiceErrorCode.UNSUPPORTED_FORMAT) return asked.getOrThrow()
        return openAt(fallbackRateHz)
    }

    private fun openAt(sampleRateHz: Int): PcmOutputLine = javaSound {
        val audioFormat = pcm16(sampleRateHz, 1)
        val line = lineFor(selection, DataLine.Info(SourceDataLine::class.java, audioFormat)) as SourceDataLine
        line.open(audioFormat, outputBufferBytes(sampleRateHz))
        line.start()
        JavaSoundOutputLine(line, PcmFormat(sampleRateHz))
    }

    private companion object {
        const val FALLBACK_RATE_HZ = 48_000
    }
}

private const val OUTPUT_BUFFERS_PER_SECOND = 8

/** An eighth of a second of mono PCM16 at [sampleRateHz], in whole frames as Java Sound requires. */
internal fun outputBufferBytes(sampleRateHz: Int): Int = sampleRateHz / OUTPUT_BUFFERS_PER_SECOND * BYTES_PER_SAMPLE

private class JavaSoundOutputLine(private val line: SourceDataLine, override val format: PcmFormat) : PcmOutputLine {
    override fun write(samples: ShortArray, count: Int): Int {
        val bytes = ByteArray(count * BYTES_PER_SAMPLE)
        for (index in 0 until count) {
            bytes[index * BYTES_PER_SAMPLE] = samples[index].toByte()
            bytes[index * BYTES_PER_SAMPLE + 1] = (samples[index].toInt() shr BYTE_BITS).toByte()
        }
        return javaSound { line.write(bytes, 0, bytes.size) } / BYTES_PER_SAMPLE
    }

    override fun playedFrames(): Long = line.longFramePosition

    override fun abort() {
        line.stop()
        line.flush()
    }

    override fun release() {
        line.close()
    }
}
