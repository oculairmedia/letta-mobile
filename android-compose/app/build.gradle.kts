import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("io.sentry.android.gradle")
    id("androidx.baselineprofile")
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
    includeProguardMapping.set(true)
    includeSourceContext.set(true)
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

android {
    namespace = "com.letta.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.letta.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { load(keystorePropsFile.inputStream()) }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else {
                val envStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotEmpty() }
                storeFile = file(envStoreFile ?: "letta-release.jks")
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
            }
        }
    }

    buildFeatures {
        compose = true
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
            java.srcDir("${project(":core").projectDir}/src/testFixtures/java")
        }
        // The `benchmark` buildType (macrobenchmark target) uses the same
        // no-op DebugPerformanceMonitor as release so benchmarks measure
        // the production code path, not the debug instrumentation stack.
        getByName("benchmark") {
            java.srcDir("src/release/java")
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
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(project(":bot"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-alpha17")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.metrics:metrics-performance:1.0.0-alpha04")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")

    // Ktor HTTP client
    implementation("io.ktor:ktor-client-core:3.4.2")
    implementation("io.ktor:ktor-client-okhttp:3.4.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")
    implementation("io.ktor:ktor-client-logging:3.4.2")
    implementation("io.ktor:ktor-client-auth:3.4.2")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // DataStore + Encrypted SharedPreferences
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.security:security-crypto:1.1.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Coil (image loading)
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Immutable collections for Compose stability
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")

    // Fuzzy search
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Vico charts
    implementation("com.patrykandpatrick.vico:compose-m3:3.1.0")

    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.4.2")
    implementation("androidx.paging:paging-compose:3.4.2")

    // Background sync
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // Sentry error tracking — initialized programmatically via
    // androidx.startup in SentryInitializer. See letta-mobile-o7ob.7.
    implementation("io.sentry:sentry-android:7.19.1")
    implementation("androidx.startup:startup-runtime:1.2.0")

    // Baseline Profile installer — reads the bundled profile and warms
    // AOT compilation on first launch. See letta-mobile-o7ob.2.1 and
    // letta-mobile-o7ob.2.4.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-client-mock:3.4.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
