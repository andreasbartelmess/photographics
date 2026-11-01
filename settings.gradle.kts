pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Lädt bei Bedarf das JDK 25 für die GNOME-Version (java-gi braucht die Foreign-Function-API)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Photographics"
include(":core", ":app")
// GNOME-Version (in Arbeit, noch nicht veröffentlicht): nur einbinden, wenn der Ordner vorhanden ist
if (file("gnome/build.gradle.kts").exists()) include(":gnome")
