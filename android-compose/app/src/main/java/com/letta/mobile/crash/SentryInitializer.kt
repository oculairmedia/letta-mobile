package com.letta.mobile.crash

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import io.sentry.android.core.SentryAndroid

/**
 * Programmatic Sentry initialization via AndroidX App Startup.
 *
 * Why not auto-init?
 * ------------------
 * `sentry-android:7.19.1` auto-init reads configuration from the
 * AndroidManifest via `ManifestMetadataReader`. That reader fetches
 * numeric keys with `Bundle.getFloat` / `Bundle.getInt` — which can
 * NOT read a `@string/...` resource reference. Any attempt to express
 * `io.sentry.traces.sample-rate` through the manifest results in a
 * warning log and the default `-1` (sampling disabled), which
 * silently neuters all transaction tagging we ship in Lane C of the
 * perf epic.
 *
 * See `letta-mobile-o7ob.7` for the original bug report and
 * `letta-mobile-o7ob.3.1` for the Sentry Performance taxonomy this
 * unblocks.
 *
 * Everything configurable via manifest placeholders (DSN, environment)
 * is read from resources (`@string/sentry_dsn`, `@string/sentry_env`,
 * `@string/sentry_traces_sample_rate`) which Gradle populates from
 * `local.properties` and the Sentry Gradle plugin.
 */
class SentryInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val res = context.resources
        val dsn = runCatching {
            res.getString(
                res.getIdentifier("sentry_dsn", "string", context.packageName),
            )
        }.getOrNull().orEmpty()

        val environment = runCatching {
            res.getString(
                res.getIdentifier("sentry_env", "string", context.packageName),
            )
        }.getOrNull() ?: "development"

        val tracesSampleRate = runCatching {
            res.getString(
                res.getIdentifier(
                    "sentry_traces_sample_rate",
                    "string",
                    context.packageName,
                ),
            ).toDoubleOrNull()
        }.getOrNull() ?: DEFAULT_TRACES_SAMPLE_RATE

        if (dsn.isBlank()) {
            Log.i(TAG, "SENTRY_DSN is blank, skipping Sentry init")
            return
        }

        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.environment = environment
            options.tracesSampleRate = tracesSampleRate
            options.isEnableAutoSessionTracking = true
            // Tracing instrumentation is enabled via the Gradle plugin
            // (sentry { tracingInstrumentation { enabled.set(true) } });
            // we don't need to configure integrations by hand here.
        }

        Log.i(
            TAG,
            "Sentry initialized env=$environment tracesSampleRate=$tracesSampleRate",
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        const val TAG = "SentryInitializer"
        const val DEFAULT_TRACES_SAMPLE_RATE = 0.2
    }
}
