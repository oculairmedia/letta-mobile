plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "com.letta.mobile.avatar.rendererweb"
        compileSdk = 36
        minSdk = 26

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
                api(project(":avatar:core"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }

        // Loopback host: serves the bundled frontend + bridges the wire
        // protocol over a local WebSocket. JVM only (desktop; Android uses
        // the WebView JS bridge instead).
        getByName("jvmMain") {
            dependencies {
                implementation("io.ktor:ktor-server-core:3.5.0")
                implementation("io.ktor:ktor-server-cio:3.5.0")
                implementation("io.ktor:ktor-server-websockets:3.5.0")
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.5.0")
                implementation("io.ktor:ktor-client-cio:3.5.0")
                implementation("io.ktor:ktor-client-websockets:3.5.0")
            }
        }
    }
}

// Package the static frontend into the jvm classpath so AvatarWebHost can
// serve it from resources — the running app needs no source checkout.
tasks.named<ProcessResources>("jvmProcessResources") {
    from("frontend") {
        into("letta-avatar-web")
        exclude("assets/**", "README.md", ".gitignore")
    }
}
