plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "com.tapll"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.0")

kotlin { jvmToolchain(17) }

dependencies {
    // The AGP public API only, and only to compile against: the app's own Android Gradle Plugin
    // provides it at runtime, so the plugin never pins a customer's AGP version.
    compileOnly(libs.agp.api)
    testImplementation(libs.junit)
}

gradlePlugin {
    // Gradle Plugin Portal (`./gradlew -p tapll-gradle-plugin publishPlugins`); keys in
    // gradle.publish.key / gradle.publish.secret, never in this repository.
    website = "https://tapll.com/docs/mappings"
    vcsUrl = "https://github.com/tapll/tapll-android"
    plugins {
        create("tapll") {
            id = "com.tapll"
            implementationClass = "com.tapll.gradle.TapllPlugin"
            displayName = "Tapll"
            description = "Generates a build id and uploads the R8 mapping to Tapll."
            tags = listOf("android", "r8", "proguard", "crash-reporting", "analytics")
        }
    }
}

tasks.test { useJUnit() }
