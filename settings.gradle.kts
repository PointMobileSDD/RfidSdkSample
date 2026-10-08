import java.util.Properties

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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
/*
 * The RFID SDK comes from Point Mobile's private Maven repository, which needs an access token.
 * Get the token from the Point Mobile portal and add it to local.properties in this folder:
 *
 *     pointmobileMavenToken=<your token>
 *
 * local.properties is never committed. A build server can pass the token as the environment
 * variable ORG_GRADLE_PROJECT_pointmobileMavenToken instead.
 */
val localProperties = Properties().apply {
    val file = settingsDir.resolve("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val pointmobileMavenToken: String =
    (localProperties.getProperty("pointmobileMavenToken")
        ?: providers.gradleProperty("pointmobileMavenToken").orNull)
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException(
            "The Point Mobile Maven token is missing. Add pointmobileMavenToken=<your token> to " +
                "local.properties in the project folder. The token comes from the Point Mobile portal; see README.md."
        )

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Point Mobile Maven repository, reached with the token above. Copy this block to your project.
        maven {
            url = uri("https://raw.githubusercontent.com/PointMobileSDD/maven/master")
            credentials(HttpHeaderCredentials::class.java) {
                name = "Authorization"
                value = "token $pointmobileMavenToken"
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
    }
}

rootProject.name = "RfidSdkSample"
include(":app")
 