import java.util.Properties

plugins {
    id("com.android.application")
    id("io.github.takahirom.roborazzi")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("io.sentry.android.gradle")
    id("androidx.baselineprofile")
    id("org.jetbrains.kotlinx.kover") // version inherited from root
}

allOpen {
    annotation("javax.inject.Singleton")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

sentry {
    autoInstallation.enabled.set(true)

    // Gate ProGuard mapping + source-context upload on the auth token being
    // present. sentry-cli needs SENTRY_AUTH_TOKEN + SENTRY_ORG + SENTRY_PROJECT
    // (and SENTRY_URL for our self-hosted instance at sentry.oculair.ca) to
    // push symbols. Without them, the upload tasks abort with
    // "An organization ID or slug is required" and fail the build. Gating
    // lets local builds (no secrets) and fork PRs succeed, while main-branch
    // CI on the oculairmedia/letta-mobile repo (which has the secrets) still
    // ships mapping files so stack traces stay deobfuscated.
    val hasSentryAuth = providers.environmentVariable("SENTRY_AUTH_TOKEN").orNull?.isNotBlank() == true
    includeProguardMapping.set(hasSentryAuth)
    includeSourceContext.set(hasSentryAuth)

    // Org / project / URL come from the environment (populated by CI from
    // repo secrets — see .github/workflows/android.yml). Hard-defaulting
    // here would lock us to a single Sentry instance; env-driven keeps the
    // build portable.
    providers.environmentVariable("SENTRY_ORG").orNull?.takeIf { it.isNotBlank() }?.let { org.set(it) }
    providers.environmentVariable("SENTRY_PROJECT").orNull?.takeIf { it.isNotBlank() }?.let { projectName.set(it) }
    providers.environmentVariable("SENTRY_URL").orNull?.takeIf { it.isNotBlank() }?.let { url.set(it) }

    tracingInstrumentation {
        enabled.set(true)
    }
}

val keystorePropsFile = rootProject.file("keystore.properties")
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        load(localPropsFile.inputStream())
    }
}

// ───────────────────────── Tag-driven versioning ─────────────────────────
//
// versionName comes from the git tag (`vX.Y.Z`) when CI builds a tagged
// release. Local / PR / untagged-main builds fall back to `git describe`
// (e.g. `0.1.0-3-gab12cd-dirty`). When no v* tag exists yet the fallback
// is `0.0.0-dev`.
//
// versionCode is derived from versionName via M*10000 + m*100 + p, so:
//   0.1.0 → 100      1.2.6 → 10206      2.0.0 → 20000
// Caps comfortably under the Play Store int limit (2_147_483_647).
//
// Override either at build time:
//   ./gradlew assembleRelease -PversionNameOverride=1.2.3-rc1
//
// Release procedure:
//   1. git tag -a v0.1.0 -m "release: 0.1.0"
//   2. git push origin v0.1.0
//   3. .github/workflows/release.yml picks it up, builds the signed APK,
//      attaches it to the GitHub Release.

@Suppress("UnstableApiUsage")
fun computeVersionName() = providers.provider {
    // 1. Explicit override (handy for one-off builds).
    providers.gradleProperty("versionNameOverride").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("GITHUB_REF_NAME").orNull
            ?.takeIf { it.startsWith("v") }
            ?.removePrefix("v")
            ?.takeIf { Regex("""\d+\.\d+\.\d+.*""").matches(it) }
}
    .orElse(
        // 3. Fallback: `git describe`. Reports e.g. `0.1.0-3-gab12cd` on a
        //    branch 3 commits past v0.1.0, or `0.1.0-3-gab12cd-dirty` with
        //    uncommitted changes. If no tags exist yet, returns `0.0.0-dev`.
        providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty", "--match", "v[0-9]*")
            workingDir = rootProject.projectDir.parentFile ?: rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.map { output ->
            output.trim()
                .removePrefix("v")
                .takeIf { it.isNotBlank() && it.matches(Regex("""\d+\.\d+\.\d+.*""")) }
                ?: "0.0.0-dev"
        },
    )

fun computeVersionCode(versionName: String): Int {
    // Strip any suffix (`-rc.1`, `-3-gab12cd`, `-dirty`) — only the
    // M.m.p portion drives the integer.
    val clean = versionName.substringBefore('-').substringBefore('+').trim()
    val parts = clean.split('.').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    // Android rejects versionCode <= 0. Floor at 1 so the `0.0.0-dev`
    // fallback (no v* tag yet, or a shallow checkout that can't reach
    // any tag) still produces a valid Android build.
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

val computedVersionName = computeVersionName().get() ?: "0.0.0-dev"
val computedVersionCode = computeVersionCode(computedVersionName)

logger.lifecycle("[versioning] versionName=$computedVersionName versionCode=$computedVersionCode")

android {
    namespace = "com.letta.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.letta.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Keep Play as the conservative dependency target for any future
        // library module that does not declare the distribution dimension.
        missingDimensionStrategy("distribution", "play")

        // Sentry config is read at runtime by [SentryInitializer] from
        // generated string resources. See letta-mobile-o7ob.7 — manifest
        // meta-data can't express a float like traces.sample-rate.
        val sentryDsn = localProps.getProperty("sentry.dsn", "")
        val sentryEnv = localProps.getProperty("sentry.environment", "development")
        resValue("string", "sentry_dsn", sentryDsn)
        resValue("string", "sentry_env", sentryEnv)
        manifestPlaceholders["SENTRY_DSN"] = sentryDsn
        manifestPlaceholders["SENTRY_ENV"] = sentryEnv
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "SYSTEM_ACCESS_FLAVOR", "\"play\"")
            buildConfigField("boolean", "ENABLE_LOCAL_SHELL", "false")
            buildConfigField("boolean", "ENABLE_SHIZUKU", "false")
            buildConfigField("boolean", "ENABLE_ROOT_TOOLS", "false")
        }
        create("sideload") {
            dimension = "distribution"
            versionNameSuffix = "-sideload"
            buildConfigField("String", "SYSTEM_ACCESS_FLAVOR", "\"sideload\"")
            buildConfigField("boolean", "ENABLE_LOCAL_SHELL", "true")
            buildConfigField("boolean", "ENABLE_SHIZUKU", "true")
            buildConfigField("boolean", "ENABLE_ROOT_TOOLS", "false")
        }
        create("root") {
            dimension = "distribution"
            versionNameSuffix = "-root"
            buildConfigField("String", "SYSTEM_ACCESS_FLAVOR", "\"root\"")
            buildConfigField("boolean", "ENABLE_LOCAL_SHELL", "true")
            buildConfigField("boolean", "ENABLE_SHIZUKU", "true")
            buildConfigField("boolean", "ENABLE_ROOT_TOOLS", "true")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { load(keystorePropsFile.inputStream()) }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else {
                val envStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull?.takeIf { it.isNotEmpty() }
                storeFile = file(envStoreFile ?: "letta-release.jks")
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull ?: ""
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull ?: ""
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
        }
        // `benchmark` mirrors release (minified + shrunk) but is
        // debuggable+profileable so Macrobenchmark can hook into it.
        // Signed with the debug cert so `installRelease` keystores aren't
        // needed on dev machines running benchmarks.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // `profileable` is what macrobench uses to read perf counters
            // without pulling the debugger in.
            isDebuggable = false
            isProfileable = true
            // Keep the app id suffix distinct so dev builds can coexist.
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                // Memory caps — see core/build.gradle.kts for rationale.
                // The app suite is the heaviest Robolectric/Hilt test shard;
                // fork more often so long CI runs do not carry retained class
                // loader state into late suites such as ProjectHomeViewModelTest.
                it.maxHeapSize = "3072m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=512m")
                it.setForkEvery(20L)
                it.filter {
                    excludeTestsMatching("*ScreenshotTest")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }

    sourceSets {
        getByName("test") {
            kotlin.directories += "src/test/java"
            kotlin.directories += "${project(":core").projectDir}/src/testFixtures/java"
        }
        // The `benchmark` buildType (macrobenchmark target) uses the same
        // no-op DebugPerformanceMonitor as release so benchmarks measure
        // the production code path, not the debug instrumentation stack.
        getByName("benchmark") {
            kotlin.directories += "src/release/java"
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// Consumes the baseline profile produced by the :baselineprofile
// module. The plugin automatically wires generated profiles into
// release APKs so install-time AOT compilation warms the hot path.
// See letta-mobile-o7ob.2.1.
baselineProfile {
    // Don't regenerate on every release build — generation requires a
    // connected device. CI runs `:app:generateBenchmarkBaselineProfile`
    // in a dedicated job.
    automaticGenerationDuringBuild = false
    // Check the generated profile into source control so local release
    // builds (without a device) still ship with a profile.
    saveInSrc = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-Xcontext-parameters",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-editagent"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-alpha17")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("app.cash.molecule:molecule-runtime:2.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.metrics:metrics-performance:1.0.0")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0-beta01")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")

    // Ktor HTTP client
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    implementation("io.ktor:ktor-client-logging:3.5.0")
    implementation("io.ktor:ktor-client-auth:3.5.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // DataStore + Encrypted SharedPreferences
    implementation("androidx.datastore:datastore-preferences:1.3.0-alpha09")
    implementation("androidx.security:security-crypto:1.1.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Coil (image loading)
    implementation("io.coil-kt.coil3:coil-compose:3.5.0-beta01")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.5.0-beta01")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Drag-to-reorder for Compose
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Immutable collections for Compose stability
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0-beta01")

    // Fuzzy search
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Vico charts
    implementation("com.patrykandpatrick.vico:compose-m3:3.2.0-next.5")

    // Timeline visualization
    implementation("io.github.pushpalroy:jetlime:4.3.0")

    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.5.0")
    implementation("androidx.paging:paging-compose:3.5.0")

    // Background sync
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Sentry error tracking — initialized programmatically via
    // androidx.startup in SentryInitializer. See letta-mobile-o7ob.7.
    implementation("io.sentry:sentry-android:8.42.0")
    implementation("androidx.startup:startup-runtime:1.2.0")

    // Baseline Profile installer — reads the bundled profile and warms
    // AOT compilation on first launch. See letta-mobile-o7ob.2.1 and
    // letta-mobile-o7ob.2.4.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:3.5.0")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.63.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.63.0")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")

    // Hilt testing
    testImplementation("com.google.dagger:hilt-android-testing:2.59.2")
    kspTest("com.google.dagger:hilt-compiler:2.59.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Test tier tasks use Root debug as the full-featured local default.
// assembleDebug still builds every distribution debug APK, but these filtered
// test aliases should exercise the artifact with all compile-time feature gates
// enabled instead of the Play-policy-constrained variant.
tasks.register<Test>("testUnit") {
    description = "Runs unit-tier tests (pure logic, <50ms per test)"
    group = "verification"

    val testTask = tasks.named("testRootDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("unit")
    }

    systemProperty("kotest.tags.include", "unit")
}

tasks.register<Test>("testIntegration") {
    description = "Runs integration-tier tests (Robolectric, Compose, ViewModels)"
    group = "verification"

    val testTask = tasks.named("testRootDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("kotest.tags.include", "integration")
}

tasks.register<Test>("testScreenshot") {
    description = "Runs screenshot-tier tests (Roborazzi visual regression)"
    group = "verification"

    val testTask = tasks.named("testRootDebugUnitTest", Test::class).get()
    mustRunAfter(testTask)
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    maxHeapSize = testTask.maxHeapSize
    jvmArgs(testTask.jvmArgs.orEmpty())
    setForkEvery(1L)

    useJUnitPlatform {
        includeEngines("junit-vintage")
    }
    filter {
        includeTestsMatching("*ScreenshotTest")
        isFailOnNoMatchingTests = false
    }
}
