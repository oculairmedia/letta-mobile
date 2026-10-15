
/*
 * Phone fixtures for the shared chat page (letta-mobile-bglj6.1): the conversations, composers,
 * ports and stand-in mascot that sharedUI's phone snapshot tests render, published once so the
 * desktop phone playground (`:desktop:runPhonePlayground`) shows the same screens live.
 *
 * Dev-only: consumed by sharedUI's jvmTest and desktop's `phonePlayground` and `test` source sets,
 * never by a production source set. Multiplatform (jvm only, for now) so sharedUI, itself
 * multiplatform, depends on a multiplatform module; add a target here when a test on another
 * platform needs the fixtures. The sources are JVM (AWT for the sample image, a reflective proxy).
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // jvmTarget 17 + catalog compileSdk/minSdk for the KMP targets (build-logic).
    id("com.letta.mobile.kmp.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        compilerOptions {
            freeCompilerArgs.addAll("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    sourceSets {
        jvmMain {
            dependencies {
                api(project(":sharedUI"))
            }
        }
    }
}
