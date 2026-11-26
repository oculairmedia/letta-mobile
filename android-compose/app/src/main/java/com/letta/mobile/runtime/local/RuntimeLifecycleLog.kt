package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import com.letta.mobile.util.flight.RotatingLineFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
