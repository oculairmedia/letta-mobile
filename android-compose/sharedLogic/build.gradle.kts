import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Phase 3c: no Compose Gradle plugins here. Projection models still use
    // compose.runtime (@Immutable/@Stable, MutableState) as a plain Maven
    // dependency — Compose UI + compiler live in :sharedUI.
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
    // Kotzilla plugin intentionally NOT applied here. The plugin generates
    // code into commonMain that pulls in JVM/Android-only SDK classes
    // (`io.kotzilla.sdk.*`), which breaks Kotlin/Native (hostNative) test
    // targets — `compileKotlinHostNative` fails on every PR. Apply the
    // plugin per-platform instead:
    //   - :app (Android) — gated by -Pkotzilla=true, applies kotzilla.json
    //     from this module's source set
    //   - :desktop (JVM) — applied unconditionally, uses the same kotzilla.json
    // The shared `KotzillaKmpMonitoring` wrapper lives in this module's
    // jvmMain (JVM-only reflection) and is callable from both.
}

kover {
    currentProject {
        createVariant("ci") {
            add("jvm")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

// Forward the opt-in flag for the live-QUIC Iroh E2E suite to the test JVM.
// Those tests dial a real loopback QUIC connection via iroh-ffi, which is flaky
// in CI runners; they are skipped (via JUnit Assume) unless this is "true".
tasks.withType<Test>().configureEach {
    System.getProperty("runIrohLiveE2E")?.let { systemProperty("runIrohLiveE2E", it) }
}

kotlin {
    android {
        namespace = "com.letta.mobile.sharedlogic"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTestBuilder {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    when {
        hostOs == "Mac OS X" && hostArch == "aarch64" -> macosArm64("hostNative")
        hostOs == "Mac OS X" -> macosX64("hostNative")
        hostOs.startsWith("Windows") -> mingwX64("hostNative")
        hostOs == "Linux" -> linuxX64("hostNative")
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core:ids"))
                api(project(":core:runtime"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                api("org.jetbrains.kotlinx:atomicfu:0.32.1")
                api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0-beta01")
                // Multiplatform date/time for the shared cron evaluator +
                // schedule projection (Phase 7). Calendar-aware next-run math
                // can't use java.time in commonMain.
                // 0.7.1 to match the version the Compose calendar library
                // (kizitonwose) pulls; 0.7.0 moved Instant to kotlin.time.
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                // Runtime-only: @Immutable/@Stable + MutableState for projection
                // / A2UI data models. No Compose UI toolkit — that is :sharedUI.
                api("org.jetbrains.compose.runtime:runtime:1.10.0")
                api("io.ktor:ktor-http:3.5.0")
                api("io.ktor:ktor-io:3.5.0")
                // Multiplatform HTTP client core for shared repository logic
                // (the engine is supplied per-platform). Lets HTTP admin
                // repositories live once in commonMain instead of being
                // duplicated per platform (letta-mobile-mqzkc).
                api("io.ktor:ktor-client-core:3.5.0")
                api("io.ktor:ktor-client-websockets:3.5.0")
            }
        }

        // Intermediate source set for Android + JVM (desktop/CLI) — transport,
        // paging contracts, QR core, and chat *projection* (not Compose UI).
        // Compose UI moved to :sharedUI in Phase 3b.
        val jvmAndAndroid by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Paging-common is Android/JVM only. Former :core:domain contracts
                // (IMessageRepository, IAllConversationsRepository) live in this
                // source set because they expose PagingData / java.time.Instant.
                api(libs.androidx.paging.common)
                // letta-mobile-gw0h1: QR Code encoder for the CLI pair command.
                // ZXing's `core` jar is pure Java (no Android-only deps).
                api("com.google.zxing:core:3.5.3")
                // CIO engine for the admin-proxy PATCH path: HttpURLConnection
                // cannot send PATCH (JDK ProtocolException).
                implementation("io.ktor:ktor-client-cio:3.5.0")
            }
        }

        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
        }

        getByName("androidMain") {
            dependsOn(jvmAndAndroid)
            dependencies {
                // Iroh AAR: brings the JVM iroh classes transitively + the
                // Android-only IrohAndroid class (JNI entry point).
                implementation("computer.iroh:iroh-android:1.1.0")
            }
        }

        getByName("androidHostTest") {
            dependsOn(jvmAndAndroidTest)
        }

        getByName("jvmMain") {
            dependsOn(jvmAndAndroid)
            dependencies {
                implementation("computer.iroh:iroh:1.1.0")
                // PNG rendering (QrRenderer.kt) needs ZXing javase — jvmMain only.
                implementation("com.google.zxing:javase:3.5.3")
                api("io.ktor:ktor-websockets:3.5.0")
            }
        }

        getByName("jvmTest") {
            dependsOn(jvmAndAndroidTest)
            dependencies {
                implementation("io.ktor:ktor-client-cio:3.5.0")
                implementation("com.google.zxing:javase:3.5.3")
                implementation("io.ktor:ktor-server-core:3.5.0")
                implementation("io.ktor:ktor-server-cio:3.5.0")
                implementation("io.ktor:ktor-server-websockets:3.5.0")
                // TEST-ONLY OkHttp engine negative control (letta-mobile-vnp3q).
                implementation("io.ktor:ktor-client-okhttp:3.5.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("app.cash.turbine:turbine:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("io.ktor:ktor-client-mock:3.5.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
            }
        }

        // Wasm/Browser — inherits commonMain only (no JVM-only deps).
        val wasmJsMain by getting {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-js:3.5.0")
            }
        }
    }
}
