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
        // JitPack hosts nextlib (com.github.anilbeesetti.*); scope it so that group
        // resolves straight from JitPack instead of first timing out on Maven Central.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github.*") }
        }
        mavenCentral()
    }
}

rootProject.name = "Timbra"
include(":app")
