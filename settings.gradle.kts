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

rootProject.name = "FitFaceStudio"

include(
    ":app",
    ":core:model",
    ":core:format",
    ":core:data",
    ":core:delivery",
    ":core:ui",
    ":feature:library",
    ":feature:editor",
)
