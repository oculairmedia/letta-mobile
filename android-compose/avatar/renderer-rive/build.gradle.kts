plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    android {
        namespace = "com.letta.mobile.avatar.rive"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // The mascot ships as a raw resource, which is the only file source the Rive runtime
        // accepts besides raw bytes. Resource support is off by default for a KMP Android library.
        androidResources.enable = true

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Desktop has no official Rive runtime, so this target carries the shared
    // mapping only. Its renderer lands when the JCEF surface hosts Rive's web
    // runtime, and it will bind the SAME common runtime this module exposes.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":avatar:core"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                // The only official Rive runtime for a target this project ships.
                api(libs.rive.android)
                implementation(compose.runtime)
                implementation(compose.foundation)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
