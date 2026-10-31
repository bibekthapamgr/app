pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "ProxyFlow"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:data")
include(":core:engine")
include(":core:testing")
include(":feature:home")
include(":feature:profiles")
include(":feature:settings")
include(":feature:logs")
include(":feature:about")
