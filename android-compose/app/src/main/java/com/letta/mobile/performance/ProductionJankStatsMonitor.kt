package com.letta.mobile.performance

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.letta.mobile.BuildConfig
import com.letta.mobile.R
import com.letta.mobile.util.Telemetry
import io.sentry.ISpan
import io.sentry.Sentry
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

object ProductionJankStatsMonitor {
    internal const val FRAME_BUDGET_MS = 16L
    internal const val MAX_DETAILED_FRAME_MEASUREMENTS = 5

    private val installed = AtomicBoolean(false)
    private val trackedActivities =
        Collections.synchronizedMap(WeakHashMap<Activity, JankStats>())

    fun install(application: Application) {
        if (BuildConfig.BUILD_TYPE != "release") {
            return
        }
        if (!installed.compareAndSet(false, true)) {
            return
        }

        val sampleRate = application.sampleRate()
        val sampled = JankSessionSampler.shouldSample(sampleRate)
        if (!sampled) {
            Telemetry.event(
                tag = "Perf",
                name = "prodJankStatsSkipped",
                "sampleRate" to sampleRate,
                "buildType" to BuildConfig.BUILD_TYPE,
            )
            return
        }

        application.registerActivityLifecycleCallbacks(JankStatsLifecycleCallbacks())
        Telemetry.event(
            tag = "Perf",
            name = "prodJankStatsEnabled",
            "sampleRate" to sampleRate,
            "buildType" to BuildConfig.BUILD_TYPE,
            "frameBudgetMs" to FRAME_BUDGET_MS,
        )
    }

    private fun Application.sampleRate(): Double {
        val raw = runCatching { getString(R.string.sentry_jankstats_sample_rate) }
            .getOrDefault("0.0")
        return raw.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
    }

    private fun ensureJankStats(activity: Activity) {
        val existing = trackedActivities[activity]
        if (existing != null) {
            existing.isTrackingEnabled = true
            return
        }

        val screenName = activity.javaClass.simpleName
        val metricsState = PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView).state
        metricsState?.putState("screen", screenName)
        metricsState?.putState("activity", screenName)

        val recorder = JankMeasurementRecorder(
            frameBudgetMs = FRAME_BUDGET_MS,
            maxDetailedFrameMeasurements = MAX_DETAILED_FRAME_MEASUREMENTS,
        )
        val frameListener = JankStats.OnFrameListener { frameData ->
            if (!frameData.isJank) {
                return@OnFrameListener
            }

            val durationMs = TimeUnit.NANOSECONDS.toMillis(frameData.frameDurationUiNanos)
            if (durationMs <= FRAME_BUDGET_MS) {
                return@OnFrameListener
            }

            val attached = withActiveTransaction { transaction ->
                recorder.record(transaction.spanContext.traceId, durationMs) { key, value ->
                    transaction.setMeasurement(key, value)
                }
            }

            Telemetry.event(
                tag = "Jank",
                name = "frame.sampled",
                "screen" to screenName,
                "durationMs" to durationMs,
                "attachedToSpan" to attached,
                level = Telemetry.Level.INFO,
            )
        }
        val stats = JankStats.createAndTrack(activity.window, frameListener)
        trackedActivities[activity] = stats
    }

    private fun withActiveTransaction(block: (ISpan) -> Unit): Boolean {
        var attached = false
        Sentry.configureScope { scope ->
            val transaction = scope.transaction ?: return@configureScope
            block(transaction)
            attached = true
        }
        return attached
    }

    private class JankStatsLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            ensureJankStats(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            ensureJankStats(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            trackedActivities[activity]?.isTrackingEnabled = false
        }

        override fun onActivityStopped(activity: Activity) {
            trackedActivities[activity]?.isTrackingEnabled = false
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            trackedActivities.remove(activity)?.isTrackingEnabled = false
        }
    }
}

internal object JankSessionSampler {
    fun shouldSample(sampleRate: Double, draw: Double = Random.nextDouble()): Boolean {
        if (sampleRate <= 0.0) return false
        if (sampleRate >= 1.0) return true
        return draw < sampleRate
    }
}

internal class JankMeasurementRecorder(
    private val frameBudgetMs: Long,
    private val maxDetailedFrameMeasurements: Int,
) {
    private var activeSpanKey: Any? = null
    private var jankFrameCount: Long = 0
    private var maxFrameDurationMs: Long = 0
    private var totalFrameDurationMs: Long = 0
    private var overBudgetDurationMs: Long = 0
    private var detailedFramesRecorded: Int = 0

    fun record(
        spanKey: Any,
        durationMs: Long,
        measurementSink: (String, Double) -> Unit,
    ) {
        if (activeSpanKey !== spanKey) {
            reset(spanKey)
        }

        jankFrameCount += 1
        maxFrameDurationMs = maxOf(maxFrameDurationMs, durationMs)
        totalFrameDurationMs += durationMs
        overBudgetDurationMs += maxOf(durationMs - frameBudgetMs, 0)

        measurementSink("jank_frame_count", jankFrameCount.toDouble())
        measurementSink("jank_frame_max_ms", maxFrameDurationMs.toDouble())
        measurementSink("jank_frame_total_ms", totalFrameDurationMs.toDouble())
        measurementSink("jank_frame_over_budget_ms", overBudgetDurationMs.toDouble())
        measurementSink("jank_frame_last_ms", durationMs.toDouble())

        if (detailedFramesRecorded < maxDetailedFrameMeasurements) {
            detailedFramesRecorded += 1
            measurementSink("jank_frame_${detailedFramesRecorded}_ms", durationMs.toDouble())
        }
    }

    private fun reset(spanKey: Any) {
        activeSpanKey = spanKey
        jankFrameCount = 0
        maxFrameDurationMs = 0
        totalFrameDurationMs = 0
        overBudgetDurationMs = 0
        detailedFramesRecorded = 0
    }
}
