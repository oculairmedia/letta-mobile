plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm()
    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf("src/commonMain/zeta", "src/commonMain/alpha"))
            dependencies {
                api("org.example:api:1.0")
            }
        }
    }
}
