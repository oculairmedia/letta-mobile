package com.letta.mobile.data.observability

/**
 * Shared Kotzilla SDK bootstrap for KMP targets (Desktop, future WasmJS).
 *
 * The Android target boots the SDK automatically at process start via a
 * ContentProvider injected by the Kotzilla Gradle plugin — no explicit call
 * needed in `Application.onCreate`. This wrapper is for the targets that do
 * NOT get the ContentProvider: Desktop's `main()` (and any future WasmJS
 * browser entry point).
 *
 * Looks up `initKotzillaConfig()` reflectively from the
 * `io.kotzilla.generated` package, which is generated at build time by the
 * Kotzilla Gradle plugin from `kotzilla.json`. The reflective lookup keeps
 * this file compiling when the plugin is not applied (e.g., CI without a
 * developer's local kotzilla.json) — without it, the import fails
 * `Unresolved reference 'generated'` and every downstream module red-fails.
 *
 * When the function isn't on the classpath (no kotzilla.json at build time,
 * or the plugin wasn't applied), the wrapper is a no-op — matching the
 * "SDK is opt-in" contract: developers who register on console.kotzilla.io
 * get sessions; everyone else gets a clean compile and no overhead.
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
    runCatching {
        val generatedClass = Class.forName("io.kotzilla.generated.KotzillaGeneratedConfigKt")
        val initMethod = generatedClass.declaredMethods.firstOrNull { it.name == "initKotzillaConfig" }
        initMethod?.invoke(null)
    }
}