package com.letta.mobile.desktop

import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Centralized crash reporting for the desktop app.
 *
 * The previous behavior surfaced uncaught throwables through Compose Desktop's
 * default error dialog, whose only text was the throwable's message. For a
 * [NoClassDefFoundError] that message is a raw internal class name (e.g.
 * `com/letta/.../DesktopMemorySurfaceKt$DesktopMemorySurface$2$1$7$1$1`), which
 * is meaningless to a user and discards the stack trace needed to diagnose it.
 *
 * This reporter writes the full stack trace (with a timestamp) to a crash log
 * under the app data dir so the failure is always recoverable after the fact,
 * and exposes a readable, actionable summary for the UI to show instead of the
 * internal class name.
 */
object DesktopCrashReporter {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Location of the rolling crash log. */
    fun crashLogPath(): Path = defaultDesktopStateDirectory().resolve("crash.log")

    /**
     * Append [throwable] (full stack trace) to the crash log. Best-effort: any
     * failure while logging is swallowed so the reporter can never itself crash
     * the handler that invoked it.
     */
    fun logCrash(throwable: Throwable, context: String? = null) {
        val rendered = render(throwable, context)
        System.err.print(rendered)
        runCatching {
            val path = crashLogPath()
            path.parent?.let(Files::createDirectories)
            Files.writeString(
                path,
                rendered,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /**
     * A user-facing one-liner for [throwable]. When the message is a raw
     * internal class name (the [NoClassDefFoundError] case), it's replaced with
     * an actionable hint instead of leaking the synthetic class path.
     */
    fun userMessage(throwable: Throwable): String {
        val message = throwable.message?.trim().orEmpty()
        val looksLikeInternalClassName = message.isNotEmpty() &&
            !message.contains(' ') &&
            (message.contains('/') || message.contains('$'))
        return if (throwable is LinkageError || looksLikeInternalClassName) {
            "Letta Desktop hit a code-loading error. This usually means the app " +
                "needs to be restarted or reinstalled after an update."
        } else {
            message.ifEmpty { throwable::class.simpleName ?: "Unexpected error" }
        }
    }

    /**
     * Install a process-wide handler so uncaught exceptions on background and
     * coroutine threads are logged with their full stack trace instead of being
     * lost to stderr. Window/composition exceptions are handled separately via
     * Compose's `LocalWindowExceptionHandlerFactory`.
     */
    fun installGlobalHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(throwable, context = "uncaught on thread '${thread.name}'")
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun render(throwable: Throwable, context: String?): String {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use(throwable::printStackTrace)
        }
        val header = buildString {
            append("===== ")
            append(OffsetDateTime.now().format(timestampFormat))
            if (context != null) {
                append(" — ")
                append(context)
            }
            append(" =====")
        }
        return "$header\n$stackTrace\n"
    }
}
