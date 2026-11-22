pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kotlin-chat"

include(":protocol")
include(":client-core")
include(":media-desktop")
include(":media-android")
include(":server")
include(":app-desktop")
include(":app-android")
include(":integration")
