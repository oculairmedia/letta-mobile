plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.letta.mobile.kmp.library")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

kover {
    currentProject {
        createVariant("ci") { add("jvm") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
}

kotlin {
    android { namespace = "com.letta.mobile.core.data" }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":sharedLogic"))
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

dependencies {
    add("kspJvmTest", libs.androidx.room.compiler)
}

tasks.withType<Test>().configureEach {
    systemProperty("room.productionSchema", rootProject.file(
        "core/android-data/schemas/com.letta.mobile.data.local.LettaDatabase/25.json",
    ).absolutePath)
}
