plugins {
    id("com.letta.mobile.android.library")
}

android {
    namespace = "com.letta.mobile.core.testutil"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:android-data"))
    api(project(":sharedLogic"))
    implementation(libs.kotlinx.coroutines.core)
    
    // Add mockk since fakes use it (relaxed relaxed = true mockk in FakeToolApi)
    implementation("io.mockk:mockk:1.14.9")
    api("androidx.datastore:datastore-preferences-core:1.3.0-alpha09")
}
