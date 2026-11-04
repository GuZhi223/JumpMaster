pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // All repositories must be declared here, not per module.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // cadb / kadb snapshots
        maven("https://jitpack.io")
    }
}

rootProject.name = "JumpMaster"
include(":app")
