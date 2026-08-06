// SPDX-License-Identifier: GPL-3.0-or-later
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
        // Scoped like the pluginManagement block above: unfiltered, google() was consulted for
        // every third-party coordinate (coroutines, guava, nextlib) and 404'd before falling
        // through — the same wasted round-trip the JitPack scoping below exists to avoid.
        google {
            content {
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
