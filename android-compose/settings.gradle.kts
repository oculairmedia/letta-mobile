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
include(":core:ids")
include(":core:runtime")
include(":core:domain")
include(":core:data")
include(":core:testutil")
include(":sharedLogic")
include(":designsystem")
include(":feature-chat")
include(":feature-editagent")
include(":desktop")
include(":cli")
include(":appserver-cli")
include(":macrobenchmark")
include(":baselineprofile")
