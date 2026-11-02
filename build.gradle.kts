import org.gradle.api.tasks.testing.Test

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val pluginBuild = gradle.includedBuild("tapll-gradle-plugin")

// `./gradlew build` covers every module, the included Gradle plugin and the sample.
tasks.named("build") {
    dependsOn(pluginBuild.task(":build"))
}

// `./gradlew test` runs every unit test suite: the SDK (Robolectric + JVM), Compose and the plugin.
tasks.register("test") {
    group = "verification"
    description = "Runs the unit tests of every module, including the Gradle plugin."
    dependsOn(":tapll:testDebugUnitTest", ":tapll-compose:testDebugUnitTest", pluginBuild.task(":test"))
}
