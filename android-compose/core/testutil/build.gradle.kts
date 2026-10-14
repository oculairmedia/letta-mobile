plugins {
    id("com.letta.mobile.android.library")
}

android {
    namespace = "com.letta.mobile.core.testutil"
}

dependencies {
    api(project(":core:android-data"))
    api(project(":sharedLogic"))
    implementation(libs.kotlinx.coroutines.core)
    
    // Add mockk since fakes use it (relaxed relaxed = true mockk in FakeToolApi)
    implementation(libs.mockk)
    api(libs.androidx.datastore.preferences.core)
}
