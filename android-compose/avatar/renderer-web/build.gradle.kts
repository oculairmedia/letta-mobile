plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "com.letta.mobile.avatar.rendererweb"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

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
                api(libs.kotlinx.coroutines.core)
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // Loopback host: serves the bundled frontend + bridges the wire
        // protocol over a local WebSocket. JVM only (desktop; Android uses
        // the WebView JS bridge instead).
        getByName("jvmMain") {
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)
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
