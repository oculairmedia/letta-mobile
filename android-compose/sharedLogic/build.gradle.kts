plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
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
        compileSdk = 37
        minSdk = 26

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

        // Intermediate source set for UI platforms (android + jvm/desktop).
        // Compose-Multiplatform UI doesn't support native targets, so we create
        // a jvmAndAndroid source set for shared chat UI (slice 1).
        val jvmAndAndroid by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Compose-Multiplatform UI dependencies for shared chat UI (slice 1).
                // foundation/ui stay aligned with the shared Compose plugin so
                // all configured KMP targets, including macOS x64, resolve.
                // Nucleus upgrades the desktop runtime separately; its Jewel
                // text-menu ABI bridge lives in DesktopJewelTheme.
                api("org.jetbrains.compose.foundation:foundation:1.10.0")
                api("org.jetbrains.compose.material3:material3:1.9.0")
                api("org.jetbrains.compose.ui:ui:1.10.0")
                // Shared Android/Desktop Markdown paint layer. Semantic streaming
                // structure lives alongside it in this source set; platform
                // modules provide only optional image/clipboard adapters.
                api("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
                api("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
                // letta-mobile-gw0h1: QR Code encoder for the CLI pair command.
                // ZXing's `core` jar is pure Java (no Android-only deps) and
                // adds the matrix encoder the CLI needs to render the
                // `letta-qr-v1.<base64url-json>` payload. Android
                // (letta-mobile-g2d2i) will reuse `core` for the server-mode
                // invite generation in a follow-on bead.
                //
                // `javase` (BufferedImage/ImageIO PNG helpers) is NOT declared
                // here: it pulls in jai-imageio, which transitively references
                // javax.imageio.spi classes absent from the Android runtime and
                // fails R8 shrinking for the play-release APK (letta-mobile-sixv8.1).
                // QrRenderer.kt — the only consumer of AWT/ImageIO in this
                // module — lives in jvmMain (desktop/CLI only, not inherited by
                // androidMain), so the `javase` dependency moves with it below.
                api("com.google.zxing:core:3.5.3")
                // Iroh QUIC transport binding (JNI-backed, Android + JVM only).
                // NOT in commonMain — native lib doesn't work with Kotlin/Native.
                // The 1.1.0 split is: computer.iroh:iroh (JVM jar) +
                // computer.iroh:iroh-android (AAR carrying the IrohAndroid
                // class for the JNI ndk_context init). See letta-mobile-eakk8.
                // We declare the dep on the per-target source sets below so
                // Android pulls the AAR (with IrohAndroid) and JVM (desktop)
                // pulls the plain jar — AAR has no JVM variant.
                // CIO engine for the admin-proxy PATCH path: HttpURLConnection
                // cannot send PATCH (JDK ProtocolException), which broke
                // admin_rpc agent.update → the drawer model switch.
                implementation("io.ktor:ktor-client-cio:3.5.0")
            }
        }

        // Wire android and jvm source sets to jvmAndAndroid
        getByName("androidMain") {
            dependsOn(jvmAndAndroid)
            dependencies {
                // Iroh AAR: brings the JVM iroh classes transitively + the
                // Android-only IrohAndroid class (the JNI entry point for
                // ndk_context::initialize_android_context, called from
                // IrohAndroidInit.kt). See letta-mobile-eakk8.
                implementation("computer.iroh:iroh-android:1.1.0")
            }
        }

        getByName("jvmMain") {
            dependsOn(jvmAndAndroid)
            dependencies {
                // Iroh JVM jar: no IrohAndroid class (Android-only), no
                // Android-specific AAR/manifest. The desktop and iroh-wrapper-cli
                // modules don't need the JNI context init.
                implementation("computer.iroh:iroh:1.1.0")
                // letta-mobile-gw0h1 / letta-mobile-sixv8.1: PNG rendering
                // (QrRenderer.kt) needs ZXing's javase (BufferedImage/ImageIO
                // helpers). Scoped to jvmMain only — it must never be
                // reachable from androidMain, since its jai-imageio
                // transitive dep references javax.imageio.spi classes that
                // don't exist on Android and break R8 shrinking.
                implementation("com.google.zxing:javase:3.5.3")
                // ktor-websockets carries the JVM WebSocketDeflateExtension
                // class used by AppServerWebSocketExtensions.kt
                // (data-efficiency Phase 2 / Q2). The class itself lives in the
                // shared commons under package io.ktor.websocket; the JVM
                // variant bundles the Deflater-backed deflate implementation.
                api("io.ktor:ktor-websockets:3.5.0")
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-cio:3.5.0")
                // letta-mobile-gw0h1: jvmTest round-trips the QR encoder
                // through ZXing's reader + BufferedImageLuminanceSource to
                // prove the CLI's PNG renderer produces a scannable image.
                implementation("com.google.zxing:javase:3.5.3")
                // Embedded WebSocket server for deterministic JVM transport tests
                // (letta-mobile-lgns8.2): connect races, close codes, malformed frames.
                implementation("io.ktor:ktor-server-core:3.5.0")
                implementation("io.ktor:ktor-server-cio:3.5.0")
                implementation("io.ktor:ktor-server-websockets:3.5.0")
                // TEST-ONLY. letta-mobile-vnp3q negative control: proves the
                // OkHttp engine still rejects applyAppServerFrameLimits() (the
                // #1064->#1077 production incident, fixed by #1078). No
                // production source set may depend on this engine.
                implementation("io.ktor:ktor-client-okhttp:3.5.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("app.cash.turbine:turbine:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                // MockEngine drives the shared HTTP repositories' commonTest.
                implementation("io.ktor:ktor-client-mock:3.5.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
            }
        }
    }
}
