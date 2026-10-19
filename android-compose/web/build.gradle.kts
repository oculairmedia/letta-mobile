import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.api.tasks.Exec

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

val irohWasmDir = rootProject.layout.projectDirectory.dir("native/iroh-wasm")
val irohWasmArtifact = irohWasmDir.file("target/wasm32-unknown-unknown/release/letta_iroh_wasm.wasm")
val generatedIrohResources = layout.buildDirectory.dir("generated/iroh-wasm")

val buildIrohWasm = tasks.register<Exec>("buildIrohWasm") {
    inputs.files(
        irohWasmDir.file("Cargo.toml"),
        irohWasmDir.file("Cargo.lock"),
        fileTree(irohWasmDir.dir("src")),
    )
    outputs.file(irohWasmArtifact)
    commandLine(
        providers.environmentVariable("CARGO").orElse("cargo").get(),
        "build",
        "--release",
        "--locked",
        "--target",
        "wasm32-unknown-unknown",
        "--manifest-path",
        irohWasmDir.file("Cargo.toml").asFile.absolutePath,
    )
}

val generateIrohWasmBindings = tasks.register<Exec>("generateIrohWasmBindings") {
    dependsOn(buildIrohWasm)
    inputs.file(irohWasmArtifact)
    outputs.dir(generatedIrohResources)
    commandLine(
        providers.environmentVariable("WASM_BINDGEN").orElse("wasm-bindgen").get(),
        irohWasmArtifact.asFile.absolutePath,
        "--target",
        "web",
        "--out-dir",
        generatedIrohResources.get().dir("iroh").asFile.absolutePath,
    )
}

// letta-mobile-ckmub.17.6: the mascot's browser binding (src/wasmJsMain/js/rive-frames), a local
// package over @rive-app/canvas-advanced pinned exactly in its package.json (2.43.1, published
// 2026-09-23). Both are bundled by the web build; the runtime's wasm is staged next to the page
// (rive/rive.wasm) and located there at runtime, so nothing is fetched from a CDN.
val riveFramesPackage = layout.projectDirectory.dir("src/wasmJsMain/js/rive-frames")
val generatedRiveResources = layout.buildDirectory.dir("generated/rive-wasm")
val stageRiveWasm = tasks.register<Sync>("stageRiveWasm") {
    dependsOn(":kotlinWasmNpmInstall")
    from(rootProject.layout.buildDirectory.dir("wasm/node_modules/@rive-app/canvas-advanced")) {
        include("rive.wasm")
    }
    into(generatedRiveResources.map { it.dir("rive") })
}

// The mascot file :avatar:renderer-rive ships (one copy; desktop reads it from the classpath),
// served beside the page as mascot/mascot.riv.
val mascotResources = rootProject.layout.projectDirectory.dir("avatar/renderer-rive/src/jvmMain/resources")

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        compilerOptions {
            // A missing graphics symbol must fail linking rather than become a runtime stub.
            freeCompilerArgs.add("-Xpartial-linkage-loglevel=error")
        }
        browser {
            commonWebpackConfig {
                outputFileName = "letta-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            resources.srcDir(generatedIrohResources)
            resources.srcDir(generatedRiveResources)
            resources.srcDir(mascotResources)
            dependencies {
                implementation(npm(riveFramesPackage.asFile))
                // Match the browser runtime: older compile APIs leave missing symbols in Wasm.
                implementation(libs.skiko.web)
                implementation(project(":sharedLogic"))
                // letta-mobile-o4ygk.4.5: the shared Compose UI (the chat page, the canvas, the
                // theme). Its Compose Multiplatform runtime, foundation, material3, ui and icons
                // are api, so the web app compiles against exactly the versions it renders with.
                implementation(project(":sharedUI"))
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs)
            }
        }
        val wasmJsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // Browser smoke tests of the shell around the shared chat page.
                implementation(libs.compose.desktop.ui.test)
            }
        }
    }
}

tasks.named("wasmJsProcessResources") {
    dependsOn(generateIrohWasmBindings)
    dependsOn(stageRiveWasm)
}

// letta-mobile-o4ygk.4.5: the Compose Gradle plugin (1.10) ships the browser its own Skiko runtime
// (skiko.mjs + skiko.wasm), older than the Skiko the shared UI links against: :sharedUI resolves
// Compose UI 1.12 (through :drawbox and coil), whose Skia bindings import functions the old
// runtime lacks, so the page fails to instantiate. Serve the runtime that matches the classpath.
// Use the same catalog entry for the Kotlin API above and the browser runtime. Matching only
// the JS runtime leaves source calls linked against an older Skiko ABI.
val webSkikoRuntimeVersion = libs.versions.web.skiko.get()
configurations.matching { it.name == "COMPOSE_SKIKO_JS_WASM_RUNTIME" }.configureEach {
    resolutionStrategy.force("org.jetbrains.skiko:skiko-js-wasm-runtime:$webSkikoRuntimeVersion")
}
