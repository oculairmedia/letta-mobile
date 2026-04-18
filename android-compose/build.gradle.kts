plugins {
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("app.cash.paparazzi") version "1.3.5" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("com.google.dagger.hilt.android") version "2.58" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.3.20" apply false
    id("io.sentry.android.gradle") version "4.14.1" apply false
    id("androidx.baselineprofile") version "1.3.4" apply false
}

// ---------------------------------------------------------------------------
// cleanKotlinIC — wipe Kotlin incremental compilation caches across all
// modules.  Run this when builds fail with .tab corruption errors instead
// of nuking the entire build/ tree.
//
//   ./gradlew cleanKotlinIC
// ---------------------------------------------------------------------------
tasks.register<Delete>("cleanKotlinIC") {
    description = "Delete Kotlin IC caches from all modules to recover from .tab corruption."
    group = "build"
    delete(allprojects.map { it.layout.buildDirectory.dir("kotlin") })
    delete(allprojects.map { it.layout.buildDirectory.dir("tmp/kotlin-classes") })
}
