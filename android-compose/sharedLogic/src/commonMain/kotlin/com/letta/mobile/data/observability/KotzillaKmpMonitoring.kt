package com.letta.mobile.data.observability

import io.kotzilla.generated.initKotzillaConfig

/**
 * Shared Kotzilla SDK bootstrap for KMP targets (Desktop, future WasmJS).
 *
 * The Android target boots the SDK automatically at process start via a
 * ContentProvider injected by the Kotzilla Gradle plugin — no explicit call
 * needed in `Application.onCreate`. This wrapper exists for the targets that
 * do NOT get the ContentProvider: Desktop's `main()` (and any future
 * WasmJS browser entry point).
 *
 * Calls `initKotzillaConfig()` from the generated `io.kotzilla.generated`
 * package, which initializes the SDK from `kotzilla.json` and registers
 * the API key/version on the global config registry. The Android auto-boot
 * path also invokes this function on the JVM side (the Android wrapper
 * does additional `KotzillaSDK.setup(application)` calls).
 *
 * The `initKotzillaConfig()` function is generated at build time by the
 * Kotzilla Gradle plugin from `kotzilla.json` and is only on the classpath
 * when the plugin is applied to a KMP module that compiles `commonMain`.
 *
 * Usage from Desktop `main()`:
 * ```kotlin
 * fun main() {
 *     com.letta.mobile.data.observability.startKotzillaMonitoring()
 *     // ... rest of app init
 * }
 * ```
 */
@Suppress("unused")
fun startKotzillaMonitoring() {
    initKotzillaConfig()
}