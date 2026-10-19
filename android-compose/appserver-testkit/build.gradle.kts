plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.letta.mobile.kmp.library")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android { namespace = "com.letta.mobile.appserver.testkit" }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":sharedLogic"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
