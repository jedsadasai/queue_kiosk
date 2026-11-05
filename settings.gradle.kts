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

rootProject.name = "QueueKiosk"

include(":app")
include(":printer-core")

// Epson's ePOS2 SDK is proprietary and cannot be redistributed. The Epson driver module is
// only built once you have dropped the SDK jar into printer-epson/libs (see printer-epson/README.md).
if (file("printer-epson/libs/ePOS2.jar").exists()) {
    include(":printer-epson")
}
