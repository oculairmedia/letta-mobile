plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.letta.mobile.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // AGP baseline-profile producer requires API 28. Do not use minSdk.
        minSdk = libs.versions.minSdkBaselineProfile.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Match :app's distribution flavor dimension so the Baseline Profile
    // consumer can resolve play/root/sideload release producer variants by
    // Gradle attributes. The generated profile is flavor-agnostic today, but
    // the producer must still publish compatible flavor variants.
    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("sideload") {
            dimension = "distribution"
        }
        create("root") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    // The androidx.baselineprofile producer plugin creates the special
    // nonMinifiedRelease/benchmarkRelease test build types it needs to publish
    // consumable baselineProfile variants. Do not replace that wiring with
    // ad-hoc release/benchmark test build types.
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

baselineProfile {
    // Use a connected device to generate the profile. CI builds that
    // lack a device can skip `generateReleaseBaselineProfile`.
    useConnectedDevices = true
}
