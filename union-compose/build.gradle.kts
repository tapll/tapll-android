plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

group = providers.gradleProperty("GROUP").get()
version = sdkVersion

kotlin {
    jvmToolchain(17)
    explicitApi()
}

android {
    namespace = "dev.useunion.compose"
    compileSdk = 35

    defaultConfig { minSdk = 24 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    publishing { singleVariant("release") { withSourcesJar() } }
}

// The Compose test activity (`ui-test-manifest`) is a debug-only dependency, so the release unit-test
// variant has no activity to host a composition in. The code under test is identical in both.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

dependencies {
    api(project(":union"))
    implementation(libs.compose.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "dev.useunion"
                artifactId = "union-compose"
                version = sdkVersion
                pom {
                    name.set("Union Compose")
                    description.set("Jetpack Compose screen tracking for the Union Android SDK.")
                    url.set("https://useunion.dev")
                }
            }
        }
    }
}
