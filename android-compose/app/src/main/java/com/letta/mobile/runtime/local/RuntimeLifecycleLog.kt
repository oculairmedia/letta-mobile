package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device runtime's lifecycle, kept past logcat (letta-mobile-by4vm.121.11): every line the
 * [com.letta.mobile.data.runtime.host.LocalAppServerHost] logs (`[lifecycle]` launch, readiness and
 * exit lines, node's stdout and stderr) plus the launch source's own timings goes to logcat at INFO
 * under [TAG] and to [RotatingLineFile] `files/embedded-lettacode/runtime-lifecycle.log`. The node
 * runs once per app process, so its first failure must survive the log buffer rotating.
 *
 * Read it with `adb shell run-as <package> cat files/embedded-lettacode/runtime-lifecycle.log`, or
 * Settings > Developer > Copy runtime log on a debug build.
 */
@Singleton
class RuntimeLifecycleLog internal constructor(private val file: RotatingLineFile) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(RotatingLineFile(runtimeLifecycleLogFile(context)))

    fun line(message: String) {
        Log.i(TAG, message)
        file.append("${Instant.now()} $message")
    }

    /** Everything kept, oldest first. */
    fun read(): String = file.read()

    companion object {
        const val TAG = "LocalAppServer"
    }
}

/** Where [RuntimeLifecycleLog] keeps its lines. */
fun runtimeLifecycleLogFile(context: Context): File =
    File(File(context.filesDir, "embedded-lettacode"), "runtime-lifecycle.log")

/**
 * Appends lines to [file], keeping at most about twice [maxBytes]: when [file] would pass
 * [maxBytes] it becomes `<name>.1` (replacing the older one) and a new [file] starts. Never throws:
 * a log that cannot be written must not take the runtime down with it.
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
        } catch (e: IOException) {
            Log.w(RuntimeLifecycleLog.TAG, "runtime lifecycle log not written: ${e.message}")
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
        const val DEFAULT_MAX_BYTES: Long = 128L * 1024
        private const val MAX_LINE_CHARS = 2_000
    }
}
