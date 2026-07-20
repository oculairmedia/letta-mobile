package com.letta.mobile.crash

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, in-app record of the app's most recent uncaught crash.
 *
 * Responsibilities:
 *  - Install a global uncaught-exception handler that writes a compact
 *    summary to app-private storage **before** delegating to the default
 *    handler (so we still die, but leave a breadcrumb for next launch).
 *  - Capture the Sentry `lastEventId` at crash time so users can reference
 *    the event in the self-hosted Sentry UI.
 *  - On app start, read any prior record and expose it as a StateFlow for
 *    the UI to surface (e.g. via a Snackbar with a "Copy id" action).
 *  - Allow dismissal, which deletes the on-disk record.
 *
 * Motivation:
 *   Emmanuel reported that when the agent chat crashed on scroll-up,
 *   nothing was surfaced — the only record was whatever ADB/logcat had
 *   captured. See letta-mobile-6wyl.
 */
@Singleton
class CrashReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class LastCrash(
        val timestamp: Long,
        val threadName: String,
        val type: String,
        val message: String,
        val stackHead: String,
        val sentryEventId: String?,
    )

    private val _lastCrash = MutableStateFlow<LastCrash?>(null)
    val lastCrash: StateFlow<LastCrash?> = _lastCrash.asStateFlow()

    private val crashFile: File
        get() = File(File(context.filesDir, CRASH_DIR).apply { mkdirs() }, CRASH_FILE)

    /**
     * Install the global uncaught-exception handler. Chains the previous
     * handler (typically Sentry's, which chains Android's default), so the
     * process still dies and Sentry still uploads — we just persist a local
     * fallback summary on the way out.
     */
    fun install() {
        // Load any prior record into the StateFlow synchronously so the UI
        // can show it as soon as composition starts.
        loadFromDiskInto(_lastCrash)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(thread, throwable)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to persist crash summary", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Clear the last-crash record (called when user dismisses the banner). */
    fun dismiss() {
        _lastCrash.value = null
        runCatching { crashFile.delete() }
    }

    private fun persistCrash(thread: Thread, throwable: Throwable) {
        val sentryId = runCatching { Sentry.getLastEventId().toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != EMPTY_SENTRY_ID }

        val stackHead = StringWriter().use { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
            sw.toString()
        }.lineSequence().take(STACK_HEAD_LINES).joinToString(separator = "\n")

        val record = LastCrash(
            timestamp = System.currentTimeMillis(),
            threadName = thread.name,
            type = throwable.javaClass.name,
            message = throwable.message.orEmpty().take(MAX_MESSAGE_LEN),
            stackHead = stackHead.take(MAX_STACK_LEN),
            sentryEventId = sentryId,
        )

        val json = JSONObject().apply {
            put("timestamp", record.timestamp)
            put("threadName", record.threadName)
            put("type", record.type)
            put("message", record.message)
            put("stackHead", record.stackHead)
            put("sentryEventId", record.sentryEventId ?: JSONObject.NULL)
            put("schemaVersion", SCHEMA_VERSION)
        }
        crashFile.writeText(json.toString())
    }

    private fun loadFromDiskInto(sink: MutableStateFlow<LastCrash?>) {
        val file = crashFile
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            sink.value = LastCrash(
                timestamp = json.optLong("timestamp"),
                threadName = json.optString("threadName"),
                type = json.optString("type"),
                message = json.optString("message"),
                stackHead = json.optString("stackHead"),
                sentryEventId = json.optString("sentryEventId", "")
                    .takeIf { it.isNotBlank() && it != "null" },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse prior crash record, discarding", t)
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val TAG = "CrashReporter"
        const val CRASH_DIR = "crash"
        const val CRASH_FILE = "last.json"
        const val SCHEMA_VERSION = 1
        const val STACK_HEAD_LINES = 20
        const val MAX_MESSAGE_LEN = 512
        const val MAX_STACK_LEN = 4_000
        // Sentry's SentryId returns this zero-UUID when no event has been captured.
        const val EMPTY_SENTRY_ID = "00000000-0000-0000-0000-000000000000"
    }
}
