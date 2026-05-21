package com.letta.mobile.performance

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.letta.mobile.util.Telemetry
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only performance monitor.
 *
 * Currently wires up:
 * - JankStats per Activity — frames >FRAME_BUDGET_MS trip a Sentry
 *   breadcrumb (category=performance, level=WARNING) plus a
 *   Telemetry.event(tag="Jank", name="frame", level=WARN).
 *
 * More subsystems (e.g. StrictMode) are added to this object incrementally
 * so that install() remains the single entry point from LettaApplication.
 *
 * The release variant ships a no-op twin in app/src/release/... with the
 * same public API so LettaApplication can call install() unconditionally.
 */
object DebugPerformanceMonitor {
    private const val FRAME_BUDGET_MS = 16L
    private const val TAG_STRICT_MODE = "StrictMode"

    private val installed = AtomicBoolean(false)
    private val frameIndex = AtomicLong(0)
    private val jankStatsByActivity = Collections.synchronizedMap(WeakHashMap<Activity, JankStats>())

    /**
     * Single-threaded bounded executor for StrictMode violation handling.
     * We deliberately don't use Dispatchers.Default to avoid unbounded
     * coroutine creation during a jank storm — a single worker coalesces
     * bursts while still keeping violation recording off the main thread.
     */
    private val strictModeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "debug-strictmode").apply {
            isDaemon = true
        }
    }

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) {
            return
        }

        installStrictMode()
        application.registerActivityLifecycleCallbacks(JankStatsLifecycleCallbacks())
        Telemetry.event(
            tag = "Perf",
            name = "debugInstrumentationEnabled",
            "strictMode" to true,
            "jankStats" to true,
            "leakCanary" to true,
        )
    }

    private fun installStrictMode() {
        // ThreadPolicy.detectAll() includes detectNetwork(). Android
        // propagates the calling thread's StrictMode policy to threads
        // it spawns, so once we install on Main the kotlinx.coroutines
        // scheduler workers (created lazily from Main on first dispatch)
        // inherit detectNetwork. Every legitimate okhttp chunked-stream
        // read on those workers — including the timeline idle long-poll
        // subscribers and the vibesync stream — trips the violation,
        // which fires penaltyLog (~30 Log.d binder calls per hit) plus
        // penaltyListener (Sentry breadcrumb + Telemetry.error). At the
        // typical idle-poll cadence that produces periodic ~150ms Main
        // stalls (visible as dropped taps in Settings). Build the policy
        // with the useful disk/resource detectors explicitly and skip
        // network, which we already verify by code review.
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectCustomSlowCalls()
            .detectResourceMismatches()
            .detectUnbufferedIo()
            .penaltyLog()
        val vmPolicyBuilder = StrictMode.VmPolicy.Builder()
            .detectAll()
            .penaltyLog()

        // penaltyListener is API 28+. On API 26-27 (our minSdk range
        // supports 26), fall through with penaltyLog() only — violations
        // still land in logcat, they just won't get mirrored to Sentry.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            threadPolicyBuilder.penaltyListener(strictModeExecutor) { violation ->
                recordStrictModeViolation(policyType = "thread", violation = violation)
            }
            vmPolicyBuilder.penaltyListener(strictModeExecutor) { violation ->
                recordStrictModeViolation(policyType = "vm", violation = violation)
            }
        }

        StrictMode.setThreadPolicy(threadPolicyBuilder.build())
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
    }

    private fun recordStrictModeViolation(policyType: String, violation: Throwable) {
        val violationType = violation.javaClass.simpleName
        val topFrame = violation.stackTrace.firstOrNull()
        val breadcrumb = Breadcrumb().apply {
            category = TAG_STRICT_MODE.lowercase()
            type = "system"
            level = SentryLevel.WARNING
            message = "$policyType violation: $violationType"
            setData("policyType", policyType)
            setData("violationType", violationType)
            topFrame?.className?.let { setData("sourceClass", it) }
            topFrame?.methodName?.let { setData("sourceMethod", it) }
            setData("stacktrace", violation.stackTraceToString())
        }
        Sentry.addBreadcrumb(breadcrumb)
        Telemetry.error(
            tag = TAG_STRICT_MODE,
            name = "violation",
            throwable = violation,
            "policyType" to policyType,
            "violationType" to violationType,
            "sourceClass" to topFrame?.className,
            "sourceMethod" to topFrame?.methodName,
        )
    }

    private fun ensureJankStats(activity: Activity) {
        val existing = jankStatsByActivity[activity]
        if (existing != null) {
            existing.isTrackingEnabled = true
            return
        }

        val screenName = activity.javaClass.simpleName
        val metricsState = PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView).state
        metricsState?.putState("screen", screenName)
        metricsState?.putState("activity", screenName)

        val frameListener = JankStats.OnFrameListener { frameData ->
            if (!frameData.isJank) {
                return@OnFrameListener
            }

            val durationMs = TimeUnit.NANOSECONDS.toMillis(frameData.frameDurationUiNanos)
            if (durationMs <= FRAME_BUDGET_MS) {
                return@OnFrameListener
            }

            val currentFrameIndex = frameIndex.incrementAndGet()
            val breadcrumb = Breadcrumb().apply {
                category = "performance"
                type = "default"
                level = SentryLevel.WARNING
                message = "Slow frame on $screenName"
                setData("screen", screenName)
                setData("durationMs", durationMs)
                setData("frameIndex", currentFrameIndex)
                setData("isJank", frameData.isJank)
            }
            Sentry.addBreadcrumb(breadcrumb)
            Telemetry.event(
                tag = "Jank",
                name = "frame",
                "screen" to screenName,
                "frameIndex" to currentFrameIndex,
                durationMs = durationMs,
                level = Telemetry.Level.WARN,
            )
        }
        val stats = JankStats.createAndTrack(activity.window, frameListener)
        jankStatsByActivity[activity] = stats
    }

    private class JankStatsLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            ensureJankStats(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            ensureJankStats(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            ensureJankStats(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            jankStatsByActivity[activity]?.isTrackingEnabled = false
        }

        override fun onActivityStopped(activity: Activity) {
            jankStatsByActivity[activity]?.isTrackingEnabled = false
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            jankStatsByActivity.remove(activity)?.isTrackingEnabled = false
        }
    }
}
