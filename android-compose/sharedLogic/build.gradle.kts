import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
        compileSdk = 37
        minSdk = 26

        withHostTestBuilder {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // letta-mobile-2don7: the A2UI renderer (moved here from designsystem)
            // uses ExperimentalMaterial3Api surfaces (TimeInput, ExposedDropdownMenuBox,
            // PrimaryTabRow, ...) the same way designsystem opted in.
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
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

        // letta-mobile-bccty: a slim Compose-Multiplatform UI source set that
        // is JVM-free / native-free — anything that needs to render on every
        // UI target (jvmAndAndroid + wasmJs) lives here without dragging
        // jvmAndAndroid's JVM-only deps (ZXing core, CIO, Iroh JNI) into
        // wasm, and without forcing Compose UI into commonMain where the
        // wasmJs native compilation unit does not yet want it.
        //
        // Kept minimal: foundation + material3 + ui + animation + lucide
        // icons is enough for the inter-agent message provenance label and
        // anything similar (small shared UI atoms).
        //
        // Declared BEFORE `jvmAndAndroid` because Kotlin DSL `creating { }`
        // blocks resolve `dependsOn(...)` eagerly, so the source set being
        // depended on must already exist.
        val composeUi by creating {
            dependsOn(commonMain.get())
            dependencies {
                api("org.jetbrains.compose.foundation:foundation:1.10.0")
                api("org.jetbrains.compose.material3:material3:1.9.0")
                api("org.jetbrains.compose.ui:ui:1.10.0")
                api("org.jetbrains.compose.animation:animation:1.10.0")
                api("com.composables:icons-lucide:1.1.0")
            }
        }

        // Intermediate source set for UI platforms (android + jvm/desktop).
        // Compose-Multiplatform UI doesn't support native targets, so we create
        // a jvmAndAndroid source set for shared chat UI (slice 1).
        val jvmAndAndroid by creating {
            dependsOn(commonMain.get())
            dependsOn(composeUi)
            dependencies {
                // Compose-Multiplatform UI dependencies for shared chat UI (slice 1).
                // foundation/ui stay aligned with the shared Compose plugin so
                // all configured KMP targets, including macOS x64, resolve.
                // Nucleus upgrades the desktop runtime separately; its Jewel
                // text-menu ABI bridge lives in DesktopJewelTheme.
                api("org.jetbrains.compose.foundation:foundation:1.10.0")
                api("org.jetbrains.compose.material3:material3:1.9.0")
                api("org.jetbrains.compose.ui:ui:1.10.0")
                // letta-mobile-2don7: A2UI renderer + LettaIcons moved here from the
                // Android-only designsystem module so desktop can render A2UI
                // surfaces with the same widget set. animation-core backs
                // AnimatedVisibility/Crossfade used by the Accordion/Tabs widgets;
                // icons-lucide backs LettaIcons; coil3 backs the A2UI Image widget
                // (coil3.compose.LocalPlatformContext is multiplatform, unlike
                // androidx.compose.ui.platform.LocalContext).
                api("org.jetbrains.compose.animation:animation:1.10.0")
                api("com.composables:icons-lucide:1.1.0")
                // Paging-common is Android/JVM only. Former :core:domain contracts
                // (IMessageRepository, IAllConversationsRepository) live in this
                // source set because they expose PagingData / java.time.Instant.
                api(libs.androidx.paging.common)
                api("io.coil-kt.coil3:coil-compose:3.5.0-beta01")
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

        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
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

        getByName("androidHostTest") {
            dependsOn(jvmAndAndroidTest)
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
            dependsOn(jvmAndAndroidTest)
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

        // Wasm/Browser source set — inherits from commonMain (NOT jvmAndAndroid,
        // which carries JVM-only deps: ZXing, CIO engine, Iroh JNI). The JS
        // Ktor engine provides browser-native fetch/WebSocket transport.
        val wasmJsMain by getting {
            dependsOn(commonMain.get())
            dependsOn(composeUi)
            dependencies {
                implementation("io.ktor:ktor-client-js:3.5.0")
            }
        }
    }
}
