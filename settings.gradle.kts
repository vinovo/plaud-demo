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

rootProject.name = "plauid"

// Path to nexasdk-bridge library (hardcoded for this demo)
val bridgeLibDir = File("/Users/paulzhu/nexa/code/nexasdk-bridge/bindings/android/app")

println("bridgeLibDir: ${bridgeLibDir.absolutePath}, exists: ${bridgeLibDir.exists()}")

if (bridgeLibDir.exists()) {
    gradle.extra["bridgePathExist"] = true
    include(":bridgeLib")
    project(":bridgeLib").projectDir = bridgeLibDir
} else {
    gradle.extra["bridgePathExist"] = false
    println("WARNING: Bridge library not found at ${bridgeLibDir.absolutePath}")
}

include(":app")
 