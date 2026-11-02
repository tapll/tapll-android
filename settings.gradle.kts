pluginManagement {
    // The Gradle plugin is an included build so the sample can apply `com.tapll` exactly
    // as a customer would, without publishing anything first.
    includeBuild("tapll-gradle-plugin")
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

rootProject.name = "tapll-android"

include(":tapll", ":tapll-compose", ":sample")
