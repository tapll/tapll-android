plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.useunion"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0")

kotlin { jvmToolchain(17) }

dependencies {
    // The AGP public API only, and only to compile against: the app's own Android Gradle Plugin
    // provides it at runtime, so the plugin never pins a customer's AGP version.
    compileOnly(libs.agp.api)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("union") {
            id = "dev.useunion.union"
            implementationClass = "dev.useunion.gradle.UnionPlugin"
            displayName = "Union"
            description = "Generates a build id and uploads the R8 mapping to Union."
        }
    }
}

tasks.test { useJUnit() }
