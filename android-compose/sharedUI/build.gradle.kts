plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

kover {
    currentProject {
        createVariant("ci") {
            add("jvm")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

kotlin {
    android {
        namespace = "com.letta.mobile.sharedui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
        }
    }

    // Phase 3b: android + jvm hosts for shared Compose UI. wasmJs remains a
    // follow-on (web keeps its local theme duplicates until then).

    sourceSets {
        commonMain {
            dependencies {
                // Domain/transport contracts and projection models stay in
                // :sharedLogic; this module renders them.
                api(project(":sharedLogic"))
                api("org.jetbrains.compose.runtime:runtime:1.10.0")
                api("org.jetbrains.compose.foundation:foundation:1.10.0")
                api("org.jetbrains.compose.material3:material3:1.9.0")
                api("org.jetbrains.compose.ui:ui:1.10.0")
                api("org.jetbrains.compose.animation:animation:1.10.0")
                api("com.composables:icons-lucide:1.1.0")
                // A2UI Image widget — coil3.compose.LocalPlatformContext is
                // multiplatform (unlike androidx LocalContext).
                api("io.coil-kt.coil3:coil-compose:3.5.0-beta01")
                // Shared Android/Desktop Markdown paint layer.
                api("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
                api("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit4)
                implementation(compose.desktop.currentOs)
                implementation(kotlin("test"))
            }
        }
    }
}
