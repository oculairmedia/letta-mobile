import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.api.tasks.Exec

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val composeVersion = "1.10.0"
val composeMaterial3Version = "1.9.0"
val composeIconsVersion = "1.7.3"
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
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
                implementation("org.jetbrains.compose.material3:material3:$composeMaterial3Version")
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                implementation("org.jetbrains.compose.material:material-icons-extended:$composeIconsVersion")
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation("io.github.vinceglb:filekit-core:0.15.0")
                implementation("io.github.vinceglb:filekit-dialogs:0.15.0")
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
