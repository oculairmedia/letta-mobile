import com.autonomousapps.DependencyAnalysisExtension

plugins {
    id("com.letta.mobile.architecture-graph")
    id("com.autonomousapps.dependency-analysis") version "3.5.1" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    // Pinned to 1.10.0 to match Jewel 0.37.0-262.4852.51, which is built against
    // Compose foundation 1.10.0. Compose 1.11.x changed the return type of
    // TextContextMenu.TextManager.getCut() (Function0 -> Action), which makes
    // Jewel's precompiled call unresolvable -> NoSuchMethodError at runtime when a
    // text-field context menu composes (letta-mobile-5icsp).
    id("org.jetbrains.compose") version "1.10.0" apply false
    id("app.cash.paparazzi") version "2.0.0-alpha05" apply false
    id("io.github.takahirom.roborazzi") version "1.63.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.3.20" apply false
    id("com.mikepenz.aboutlibraries.plugin.android") version "14.2.1" apply false
    id("io.sentry.android.gradle") version "6.8.1" apply false
    id("androidx.baselineprofile") version "1.5.0-alpha06" apply false
}

// Dependency Analysis is opt-in and advisory: AGP 9.2 is newer than the plugin's
// supported AGP range and currently fails while creating Android test advice tasks.
// CI probes it with continue-on-error so upgrades become visible without blocking.
// Even when enabled, KMP source-set attribution is incomplete; the architecture
// graph is authoritative for KMP targets, source sets, and declared edges.
if (providers.gradleProperty("dependencyAnalysisAdvisory").orNull == "true") {
    apply(plugin = "com.autonomousapps.dependency-analysis")
    extensions.configure<DependencyAnalysisExtension> {
        issues { all { onAny { severity("ignore") } } }
    }
    subprojects {
        // Limit advice to plain JVM modules until AGP 9.2 and KMP are supported.
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            apply(plugin = "com.autonomousapps.dependency-analysis")
            extensions.configure<DependencyAnalysisExtension> {
                issues { all { onAny { severity("ignore") } } }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Kotlin 2.4.0 + Dagger/Hilt metadata compatibility (letta-mobile, 2026-06-24)
// ---------------------------------------------------------------------------
// Dagger/Hilt 2.59.2 bundles a kotlin-metadata-jvm that only understands
// Kotlin Metadata version <= 2.3.0, so on Kotlin 2.4.0 the Hilt annotation
// processor fails: "Provided Metadata instance has version 2.4.0, while
// maximum supported version is 2.3.0." This is upstream Dagger lag (issue
// google/dagger#5190 / #5180). The maintainer-endorsed workaround is to force
// a 2.4-aware kotlin-metadata-jvm onto every module's KSP processor classpath.
// Remove once Dagger ships a release that bundles a 2.4+ metadata reader.
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
        }
    }
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

subprojects {
    plugins.withId("com.google.devtools.ksp") {
        afterEvaluate {
            tasks.matching { task -> task.name.startsWith("ksp") && task.name.endsWith("Kotlin") }.forEach { kspTask ->
                val kspVariant = kspTask.name
                    .removePrefix("ksp")
                    .removeSuffix("Kotlin")
                    .replaceFirstChar { it.lowercase() }
                val kotlinOutputDir = layout.buildDirectory.dir("generated/ksp/$kspVariant/kotlin").get().asFile
                val classOutputDir = layout.buildDirectory.dir("generated/ksp/$kspVariant/classes").get().asFile
                val prepareOutputDirs = tasks.register("prepare${kspTask.name.replaceFirstChar { it.uppercase() }}OutputDirs") {
                    outputs.dir(kotlinOutputDir)
                    outputs.dir(classOutputDir)
                    doLast {
                        kotlinOutputDir.mkdirs()
                        classOutputDir.mkdirs()
                    }
                }
                kspTask.dependsOn(prepareOutputDirs)
                kspTask.doFirst {
                    kotlinOutputDir.mkdirs()
                    classOutputDir.mkdirs()
                }
                kspTask.doLast {
                    kotlinOutputDir.mkdirs()
                    classOutputDir.mkdirs()
                }
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
    kover(project(":core:domain"))
    kover(project(":core:data"))
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
