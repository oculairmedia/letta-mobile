plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.letta.mobile.buildlogic"

val GRADLE_USER_HOME_PROPERTY = "letta.gradle.user.home"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.abi.tools)
    implementation(libs.dokka.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("apiDocumentation") {
            id = "com.letta.mobile.api-documentation"
            implementationClass = "com.letta.mobile.docs.ApiDocumentationPlugin"
        }
        create("architectureGraph") {
            id = "com.letta.mobile.architecture-graph"
            implementationClass = "com.letta.mobile.architecture.ArchitectureGraphPlugin"
        }
        create("embeddedRuntime") {
            id = "com.letta.mobile.embedded-runtime"
            implementationClass = "com.letta.mobile.embedded.EmbeddedRuntimePlugin"
        }
        create("kmpLibrary") {
            id = "com.letta.mobile.kmp.library"
            implementationClass = "com.letta.mobile.kmp.KmpLibraryPlugin"
        }
        create("brand") {
            id = "com.letta.mobile.brand"
            implementationClass = "com.letta.mobile.brand.BrandPlugin"
        }
        create("robolectricDeps") {
            id = "com.letta.mobile.robolectric-deps"
            implementationClass = "com.letta.mobile.robolectric.RobolectricDepsPlugin"
        }
        create("jni16Kb") {
            id = "com.letta.mobile.jni-16kb"
            implementationClass = "com.letta.mobile.jni.Jni16KbPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // TestKit fixtures start with an empty Gradle user home; they read this one's dependency cache
    // (GRADLE_RO_DEP_CACHE) before going to the network. See FixtureRunner.
    systemProperty(GRADLE_USER_HOME_PROPERTY, gradle.gradleUserHomeDir.absolutePath)
}
