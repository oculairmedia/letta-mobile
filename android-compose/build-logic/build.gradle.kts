plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.letta.mobile.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("architectureGraph") {
            id = "com.letta.mobile.architecture-graph"
            implementationClass = "com.letta.mobile.architecture.ArchitectureGraphPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
