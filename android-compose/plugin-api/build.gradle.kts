import com.letta.mobile.kmp.hostNative
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The Kotlin SPI a jvm-runtime canvas plugin implements (plan section 3.2, letta-mobile-s416w.26).
// Plugin authors compile against this module, so it stays small: no project dependencies, and only
// kotlinx-coroutines-core and kotlinx-serialization-json. Its DTOs are the LCP wire shapes, so
// :sharedLogic depends on it (KMP -> KMP) and carries the same targets.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // jvmTarget 17 + catalog compileSdk/minSdk for the KMP targets (build-logic).
    id("com.letta.mobile.kmp.library")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

// The SPI version; its major is the manifest's `contract.version` (PluginApi.CONTRACT_VERSION).
group = "com.letta.mobile"
version = "1.1.0"

kotlin {
    explicitApi()

    android {
        namespace = "com.letta.mobile.plugin.api"
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    hostNative()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
