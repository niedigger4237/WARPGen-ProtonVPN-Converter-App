pluginManagement {
    repositories {
        google()
        if (System.getenv("WARP_MAVEN_MIRROR") == "google") {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        } else {
            mavenCentral()
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        if (System.getenv("WARP_MAVEN_MIRROR") == "google") {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        } else {
            mavenCentral()
        }
    }
}
rootProject.name = "WarpProtonDesktop"
