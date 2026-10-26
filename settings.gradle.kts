pluginManagement {
    val localProperties = java.util.Properties().apply {
        val propertiesFile = file("local.properties")
        if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
    }
    val localMavenRepositoryUrl = localProperties.getProperty("sparkwriter.mavenRepositoryUrl")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    repositories {
        localMavenRepositoryUrl?.let { repositoryUrl: String ->
            maven {
                name = "localSparkWriterMirror"
                url = uri(repositoryUrl)
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val localProperties = java.util.Properties().apply {
        val propertiesFile = file("local.properties")
        if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
    }
    val localMavenRepositoryUrl = localProperties.getProperty("sparkwriter.mavenRepositoryUrl")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    repositories {
        localMavenRepositoryUrl?.let { repositoryUrl: String ->
            maven {
                name = "localSparkWriterMirror"
                url = uri(repositoryUrl)
            }
        }
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "SparkWriterDroid"

include(
    ":app",
    ":core-model",
    ":manifest-runtime",
    ":usb-writer",
    ":storage",
    ":shared-test-fixtures",
)
