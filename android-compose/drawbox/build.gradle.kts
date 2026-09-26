// Vendored DrawBox (akshay2211/DrawBox v2.1.0, Apache-2.0). See VENDORED.md for what came from
// where and every change made here, so fixes can be offered back upstream.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // Upstream also targets iOS, JS and wasm; Letta ships Android and the JVM desktop only.
    android {
        namespace = "io.ak1.drawbox"
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

    sourceSets {
        commonMain {
            dependencies {
                // Exactly what the published io.ak1:drawbox:2.1.0 declared, so swapping the binary for
                // this source changes nothing in the resolved dependency graph.
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
                implementation("org.jetbrains.compose.ui:ui:1.11.1")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-rc01")
                implementation(libs.kotlinx.serialization.json)
                // DrawBoxController is a ViewModel; exposed so hosts resolve the hierarchy.
                api(libs.androidx.lifecycle.viewmodel)
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
