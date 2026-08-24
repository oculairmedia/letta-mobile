plugins {
    id("com.letta.mobile.android.library")
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

android {
    namespace = "com.letta.mobile.core"

    buildFeatures {
        buildConfig = true
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
                it.maxParallelForks = 1
                it.forkEvery = 50L
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":sharedLogic"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Compose runtime for @Stable and @Immutable annotations
    implementation("androidx.compose.runtime:runtime:1.8.3")

    // androidx.tracing — Perfetto tracing integration
    implementation("androidx.tracing:tracing:1.2.0")

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(libs.kotlinx.coroutines.android)

    implementation("androidx.paging:paging-runtime-ktx:3.5.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0-alpha09")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("com.google.android.material:material:1.14.0")

    api("androidx.room:room-runtime:2.8.4")
    api("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
}

// Test tier tasks
tasks.register<Test>("testUnit") {
    description = "Runs unit-tier tests"
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
    description = "Runs integration-tier tests"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("kotest.tags.include", "integration")
}
