// Live plugin pages on the canvas (letta-mobile-s416w.13, plan section 7.3): the live-view
// renderer the shells register, the page policy every host shares, and the platform hosts
// (androidMain: the sandboxed WebView; jvmMain: desktop JCEF, letta-mobile-s416w.14).
// KMP like :sharedUI, and it depends on :sharedUI and :sharedLogic only.
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
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    android {
        namespace = "com.letta.mobile.pluginview"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // Robolectric host tests of the WebView host read the merged manifest and resources.
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }

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
                api(project(":sharedUI"))
                api(project(":sharedLogic"))
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit4)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
