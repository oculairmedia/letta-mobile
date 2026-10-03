import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.api.tasks.Exec

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
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
            dependencies {
                implementation(project(":sharedLogic"))
                // letta-mobile-o4ygk.4.5: the shared Compose UI (the chat page, the canvas, the
                // theme). Its Compose Multiplatform runtime, foundation, material3, ui and icons
                // are api, so the web app compiles against exactly the versions it renders with.
                implementation(project(":sharedUI"))
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.filekit.core.chat)
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
}

// letta-mobile-o4ygk.4.5: the Compose Gradle plugin (1.10) ships the browser its own Skiko runtime
// (skiko.mjs + skiko.wasm), older than the Skiko the shared UI links against: :sharedUI resolves
// Compose UI 1.12 (through :drawbox and coil), whose Skia bindings import functions the old
// runtime lacks, so the page fails to instantiate. Serve the runtime that matches the classpath.
// Keep this in step with the org.jetbrains.skiko:skiko version on wasmJsRuntimeClasspath; it goes
// once the Compose plugin matches the resolved Compose (letta-mobile-o4ygk.4.10).
val webSkikoRuntimeVersion = "0.150.1"
configurations.matching { it.name == "COMPOSE_SKIKO_JS_WASM_RUNTIME" }.configureEach {
    resolutionStrategy.force("org.jetbrains.skiko:skiko-js-wasm-runtime:$webSkikoRuntimeVersion")
}
