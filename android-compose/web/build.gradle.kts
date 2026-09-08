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
                implementation(libs.compose.web.runtime)
                implementation(libs.compose.web.foundation)
                implementation(libs.compose.web.material3)
                implementation(libs.compose.web.ui)
                implementation(libs.compose.web.material.icons)
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
            }
        }
    }
}

tasks.named("wasmJsProcessResources") {
    dependsOn(generateIrohWasmBindings)
}
