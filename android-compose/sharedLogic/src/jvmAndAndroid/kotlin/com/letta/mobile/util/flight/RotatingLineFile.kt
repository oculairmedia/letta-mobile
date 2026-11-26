package com.letta.mobile.util.flight

import com.letta.mobile.util.TelemetryRecorder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Appends lines to [file], keeping at most about twice [maxBytes]: when [file] would pass [maxBytes] it becomes `<name>.1`
 * (replacing the older one) and a new [file] starts. Never throws: a log that cannot be written must not take its caller down
 * with it. Each append closes the file, so a written line is in the kernel's hands and survives the process being killed.
 * Used by the on-device runtime's lifecycle log and by the flight record.
 */
class RotatingLineFile(private val file: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val previous = File(file.parentFile, "${file.name}.1")

    @Synchronized
    fun append(line: String) {
        try {
            file.parentFile?.mkdirs()
            val bytes = (line.take(MAX_LINE_CHARS) + "\n").toByteArray()
            if (file.exists() && file.length() + bytes.size > maxBytes) rotate()
            file.appendBytes(bytes)
        } catch (_: IOException) {
            // Unwritable (full disk, a file where the directory should be): the line is lost, the caller carries on.
        }
    }

    /** The previous file then the current one; empty when nothing was written. */
    @Synchronized
    fun read(): String = listOf(previous, file).filter(File::exists).joinToString("") { it.readText() }

    private fun rotate() {
        previous.delete()
        if (!file.renameTo(previous)) file.delete()
    }

    companion object {
        /** The cap of one file when the caller names none. */
        const val DEFAULT_MAX_BYTES: Long = 128L * 1024
        private const val MAX_LINE_CHARS = 2_000
    }
}

/** A [FlightRecordStore] on disk: [RotatingLineFile] `flight-record.log` (and `.1`) in [directory], at most about 2 x [maxBytesPerFile]. */
class FileFlightRecordStore(directory: File, maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE) : FlightRecordStore {
    private val file = RotatingLineFile(File(directory, FILE_NAME), maxBytesPerFile)

    override fun append(lines: List<String>) = lines.forEach(file::append)

    override fun read(): List<String> = file.read().lines().filter(String::isNotEmpty)

    companion object {
        /** The record's file name in its directory; the rotated older half is this plus `.1`. */
        const val FILE_NAME: String = "flight-record.log"

        /** One file's cap: two of them hold several hours of normal use at the policy's rate limits. */
        const val DEFAULT_MAX_BYTES_PER_FILE: Long = 256L * 1024
    }
}

/** Installs the flight record for [recorder] into `flight-record.log` under [directory], written from the I/O dispatcher. */
fun installFileFlightRecording(recorder: TelemetryRecorder, directory: File, build: String): AutoCloseable =
    FlightRecording.install(recorder, FileFlightRecordStore(directory), CoroutineScope(SupervisorJob() + Dispatchers.IO), build)
