plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("app.cash.paparazzi") version "2.0.0-alpha05" apply false
    id("io.github.takahirom.roborazzi") version "1.63.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.3.8" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.3.20" apply false
    id("io.sentry.android.gradle") version "6.8.1" apply false
    id("androidx.baselineprofile") version "1.5.0-alpha06" apply false
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

// ---------------------------------------------------------------------------
// Build cache policy
// ---------------------------------------------------------------------------
// letta-mobile-pywa: Keep Gradle's build cache enabled for the broad set of
// cacheable Kotlin, Java, test, and Android tasks, but avoid caching AGP's main
// manifest processing outputs. This narrows the previous global
// org.gradle.caching=false workaround to the task family that produced cache
// packing flakes such as processDebugMainManifest.
subprojects {
    tasks.configureEach {
        if (name.startsWith("process") && name.endsWith("MainManifest")) {
            outputs.cacheIf("AGP main manifest processing cache packing is disabled; see letta-mobile-pywa") {
                false
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Java 26 test runtime compatibility
// ---------------------------------------------------------------------------
// CI runs the Gradle unit-test tasks on Java 26. Robolectric currently brings
// ASM 9.8 transitively, which cannot read Java 26 class files during sandbox
// instrumentation. Keep the override scoped to the ASM module family until
// Robolectric updates its transitive dependency.
val java26CompatibleAsmVersion = "9.9.1"
val java26CompatibleAsmModules = setOf(
    "asm",
    "asm-analysis",
    "asm-commons",
    "asm-tree",
    "asm-util",
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.ow2.asm" && requested.name in java26CompatibleAsmModules) {
                useVersion(java26CompatibleAsmVersion)
                because("Java 26 unit tests need ASM support for Java 26 class files.")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Kover — aggregated code coverage reports (HTML for humans, XML for CI)
//
//   ./gradlew koverHtmlReport
//
// Use the merged total Kover report for refactor safety checks.
//
// The root module acts as the merging module. Submodules declare the plugin
// without version (inherited from here). Add a kover(project(":name"))
// dependency below for each module whose coverage should be aggregated.
// ---------------------------------------------------------------------------
dependencies {
    kover(project(":app"))
    kover(project(":core"))
    kover(project(":sharedLogic"))
    kover(project(":designsystem"))
    kover(project(":feature-chat"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Hilt/Dagger generated code
                    "*_Factory",
                    "*_Factory\$*",
                    "*_MembersInjector",
                    "*_MembersInjector\$*",
                    "*_HiltModules",
                    "*_HiltModules\$*",
                    "dagger.hilt.*",
                    "hilt_aggregated_deps.*",
                    // Databinding & generated
                    "*.databinding.*",
                    "*.BR",
                    // Compose compiler generated
                    "*_ComposableSingletons*",
                    // DI wiring classes
                    "com.letta.mobile.di.*",
                )
            }
        }

        total {
            html {
                onCheck = false
            }
            xml {
                onCheck = false
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Test parallelism tuning — enable concurrent test JVM workers
//
// Default Gradle behavior: maxParallelForks=1 (serial), forkEvery=0 (unlimited).
// This configuration enables parallel test execution across modules while
// maintaining stability through conservative resource allocation.
//
// Rationale:
// - maxParallelForks = (cores / 2).coerceAtLeast(1) balances parallelism vs.
//   memory pressure, disk I/O contention, and CPU context-switching overhead.
// - forkEvery=100 recycles JVM workers to prevent memory leaks and shared
//   static state accumulation across long test suites.
// - Forked JVMs are isolated from the main build process, preventing classpath
//   pollution and shared static state leakage into the build itself.
//
// See: https://docs.gradle.org/current/userguide/java_testing.html
//      https://docs.gradle.org/current/userguide/performance.html
// ───────────────────────────────────────────────────────────────────────────
subprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

        // CI runs tests on Java 26. These flags acknowledge the reflective/native
        // access used by current test/runtime dependencies (MockK/ByteBuddy,
        // Conscrypt, and protobuf-backed DataStore) so warning noise does not
        // mask real failures. Keep this conditional so local JDK 17-25 builds do
        // not receive launcher flags they may not recognize.
        val testJvmFeatureVersion = JavaVersion.current().majorVersion.toIntOrNull() ?: 0
        if (testJvmFeatureVersion >= 26) {
            jvmArgs(
                "-Xshare:off",
                "--enable-native-access=ALL-UNNAMED",
                "--enable-final-field-mutation=ALL-UNNAMED",
                "--sun-misc-unsafe-memory-access=allow",
            )
        }
    }
}
