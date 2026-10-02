import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Phone fixtures for the shared chat page (letta-mobile-bglj6.1): the conversations, composers,
 * ports and stand-in mascot that sharedUI's phone snapshot tests render, published once so the
 * desktop phone playground (`:desktop:runPhonePlayground`) shows the same screens live.
 *
 * Dev-only: consumed by sharedUI's jvmTest and desktop's `phonePlayground` source set, never by a
 * production source set.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    // sharedUI's jvm target (and its tests) are JVM 17.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    api(project(":sharedUI"))
}
