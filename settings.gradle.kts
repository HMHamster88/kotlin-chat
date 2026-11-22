pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
include(":server")
include(":app-desktop")
include(":integration")
