// The conformance kit for jvm canvas plugins (letta-mobile-s416w.26): a FakePluginHost and
// PluginConformance.run, which a plugin author calls from their own tests. Kept out of :plugin-api
// so the API jar carries only the contract. jvm only: plugins are jars.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // jvmTarget 17 + catalog compileSdk/minSdk for the KMP targets (build-logic).
    id("com.letta.mobile.kmp.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

group = "com.letta.mobile"
version = "1.0.0"

kotlin {
    explicitApi()

    jvm()

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                api(project(":plugin-api"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
