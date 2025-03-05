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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS) // Force settings repositories
    repositories {
        google()
        mavenCentral()
        maven(url = "https://sdk.tapjoy.com/") // Add Tapjoy
    }
}

rootProject.name = "PixelPayout"
include(":app")
