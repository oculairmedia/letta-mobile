plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
}

allOpen {
    annotation("javax.inject.Singleton")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

android {
    namespace = "com.letta.mobile.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
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
                // Memory caps for unit-test JVM workers. Robolectric + Hilt
                // generated code + Ktor clients accumulate across tests in a
                // single JVM, so we cap heap + metaspace and recycle workers
                // every 100 tests to keep long suites inside the cap.
                // See: letta-mobile OOM in ConnectivityMonitorTest (Apr 2026).
                it.maxHeapSize = "1536m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384m")
                it.setForkEvery(100L)
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")

    // Compose runtime for @Stable and @Immutable annotations
    implementation("androidx.compose.runtime:runtime:1.8.3")

    // androidx.tracing — emits systrace/Perfetto sections so Telemetry
    // timers are visible in ui.perfetto.dev alongside framework traces.
    // Used as an integration layer inside Telemetry; beginAsyncSection /
    // endAsyncSection power the cross-coroutine Timer class.
    implementation("androidx.tracing:tracing:1.2.0")

    implementation("io.ktor:ktor-client-core:3.4.2")
    implementation("io.ktor:ktor-client-okhttp:3.4.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")
    implementation("io.ktor:ktor-client-logging:3.4.2")
    implementation("io.ktor:ktor-client-auth:3.4.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.paging:paging-runtime-ktx:3.4.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    api("androidx.room:room-runtime:2.7.0")
    api("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

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
}

// Test tier tasks
tasks.register<Test>("testUnit") {
    description = "Runs unit-tier tests (pure logic, <50ms per test)"
    group = "verification"
    
    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
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
    
    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    
    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("kotest.tags.include", "integration")
}
