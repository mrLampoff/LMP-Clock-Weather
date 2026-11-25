
pluginManagement {
    repositories {
        // Google-hosted Maven Central mirror
        maven {
            name = "GoogleMavenCentralMirror"
            url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/")
        }

        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // Google-hosted Maven Central mirror
        maven {
            name = "GoogleMavenCentralMirror"
            url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/")
        }

        google()
        mavenCentral()
    }
}

rootProject.name = "LMP Clock & Weather"
include(":app")
