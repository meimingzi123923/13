pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")   // Sora Editor / libsu 等
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://chaquo.com/maven")   // Chaquopy Python
    }
}

rootProject.name = "PocketCodeStudio"
include(":app")
