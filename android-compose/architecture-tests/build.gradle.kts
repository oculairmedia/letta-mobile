plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.archunit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    dependsOn(":core:ids:jvmMainClasses", ":sharedLogic:jvmMainClasses")
    useJUnitPlatform()
    systemProperty("architecture.projectRoot", rootProject.projectDir.parentFile.absolutePath)
}

tasks.test {
    filter {
        excludeTestsMatching("*RepositoryArchitectureTest")
    }
}

val architectureTest = tasks.register<Test>("architectureTest") {
    description = "Run Kotlin source and JVM bytecode architecture checks (hard fail)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("*RepositoryArchitectureTest")
    }
    reports.junitXml.required.set(true)
    reports.html.required.set(true)
}
