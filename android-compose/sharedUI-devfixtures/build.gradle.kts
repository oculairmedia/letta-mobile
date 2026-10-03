import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        compilerOptions {
            // sharedUI's jvm target (and its tests) are JVM 17.
            jvmTarget.set(JvmTarget.JVM_17)
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
