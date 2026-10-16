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
 *  - Surface each crash at most once: dismissal records the crash's
 *    timestamp in a sidecar marker so later launches stay quiet until a
 *    newer crash is persisted. The diagnostic record itself stays on disk
 *    and remains readable via [readDiagnosticRecord].
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

    private val crashDir: File
        get() = File(context.filesDir, CRASH_DIR).apply { mkdirs() }

    private val crashFile: File
        get() = File(crashDir, CRASH_FILE)

    /** Holds the timestamp of the last crash already surfaced to the user. */
    private val surfacedFile: File
        get() = File(crashDir, SURFACED_FILE)

    /**
     * Install the global uncaught-exception handler. Chains the previous
     * handler (typically Sentry's, which chains Android's default), so the
     * process still dies and Sentry still uploads — we just persist a local
     * fallback summary on the way out.
     */
    fun install() {
        // Publish a prior record synchronously so the UI can show it as soon
        // as composition starts, but only if it has not been surfaced yet.
        _lastCrash.value = readDiagnosticRecord()
            ?.takeIf { it.timestamp > readSurfacedTimestamp() }

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

    /**
     * Dismiss the notification without deleting the diagnostic record, and
     * remember that this crash was surfaced so later launches do not re-show it.
     */
    fun dismiss() {
        val shown = _lastCrash.value ?: return
        _lastCrash.value = null
        runCatching { surfacedFile.writeText(shown.timestamp.toString()) }
            .onFailure { Log.w(TAG, "Failed to record surfaced crash", it) }
    }

    /** The most recent persisted crash, whether or not it was already surfaced. */
    fun readDiagnosticRecord(): LastCrash? {
        val file = crashFile
        if (!file.exists()) return null
        return try {
            parseRecord(JSONObject(file.readText()))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse prior crash record, discarding", t)
            runCatching { file.delete() }
            null
        }
    }

    private fun readSurfacedTimestamp(): Long =
        runCatching { surfacedFile.takeIf { it.exists() }?.readText()?.trim()?.toLong() }
            .getOrNull() ?: Long.MIN_VALUE

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

    private fun parseRecord(json: JSONObject): LastCrash = LastCrash(
        timestamp = json.optLong("timestamp"),
        threadName = json.optString("threadName"),
        type = json.optString("type"),
        message = json.optString("message"),
        stackHead = json.optString("stackHead"),
        sentryEventId = json.optString("sentryEventId", "")
            .takeIf { it.isNotBlank() && it != "null" },
    )

    private companion object {
        const val TAG = "CrashReporter"
        const val CRASH_DIR = "crash"
        const val CRASH_FILE = "last.json"
        const val SURFACED_FILE = "last-surfaced"
        const val SCHEMA_VERSION = 1
        const val STACK_HEAD_LINES = 20
        const val MAX_MESSAGE_LEN = 512
        const val MAX_STACK_LEN = 4_000
        // Sentry's SentryId returns this zero-UUID when no event has been captured.
        const val EMPTY_SENTRY_ID = "00000000-0000-0000-0000-000000000000"
    }
}
