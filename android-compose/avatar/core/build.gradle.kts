import com.letta.mobile.kmp.hostNative
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlin.multiplatform")
    // jvmTarget 17 + catalog compileSdk/minSdk for the KMP targets (build-logic).
    id("com.letta.mobile.kmp.library")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
}

kotlin {
    android {
        namespace = "com.letta.mobile.avatar.core"
    }

    jvm()

    // The web client renders :sharedUI, which shows the mascot (letta-mobile-o4ygk.4).
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    hostNative()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                api(libs.kotlinx.serialization.json)
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
