pluginManagement {
    // The Gradle plugin is an included build so the sample can apply `dev.useunion.union` exactly
    // as a customer would, without publishing anything first.
    includeBuild("union-gradle-plugin")
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

rootProject.name = "union-android"

include(":union", ":union-compose", ":sample")
