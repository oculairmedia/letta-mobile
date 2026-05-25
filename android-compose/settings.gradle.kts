pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LettaMobile"
include(":app")
include(":core")
include(":sharedLogic")
include(":designsystem")
include(":feature-chat")
include(":feature-editagent")
include(":cli")
include(":macrobenchmark")
include(":baselineprofile")
