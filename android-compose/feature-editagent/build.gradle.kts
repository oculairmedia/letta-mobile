plugins {
    id("com.letta.mobile.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover") // version inherited from root
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

allOpen {
    annotation("javax.inject.Singleton")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

android {
    namespace = "com.letta.mobile.feature.editagent"

    buildFeatures {
        compose = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                it.maxHeapSize = "1536m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384m")
                it.forkEvery = 100L
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
        getByName("test") {
            kotlin.directories += "src/test/java"
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(project(":core:android-data"))
    testImplementation(project(":core:testutil"))
    implementation(project(":designsystem"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.molecule.runtime)
    implementation(libs.coil3.compose)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testRuntimeOnly(libs.junit.vintage.engine)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register<Test>("testUnit") {
    description = "Runs unit-tier tests (pure logic, <50ms per test)"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("unit")
    }

    systemProperty("kotest.tags.include", "unit")
}

tasks.register<Test>("testIntegration") {
    description = "Runs integration-tier tests (Robolectric, Compose, ViewModels)"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("kotest.tags.include", "integration")
}
