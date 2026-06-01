plugins {
    id("com.android.library")
}

android {
    namespace = "com.letta.mobile.core.testutil"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:data"))
    api(project(":sharedLogic"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    
    // Add mockk since fakes use it (relaxed relaxed = true mockk in FakeToolApi)
    implementation("io.mockk:mockk:1.14.9")
    api("androidx.datastore:datastore-preferences-core:1.3.0-alpha09")
}
