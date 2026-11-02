plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

group = providers.gradleProperty("GROUP").get()
version = sdkVersion

kotlin {
    jvmToolchain(17)
    explicitApi()
}

android {
    namespace = "com.tapll.compose"
    compileSdk = 35

    defaultConfig { minSdk = 24 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// The Compose test activity (`ui-test-manifest`) is a debug-only dependency, so the release unit-test
// variant has no activity to host a composition in. The code under test is identical in both.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

dependencies {
    api(project(":tapll"))
    implementation(libs.compose.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Maven Central through the Central Portal: signed, with sources and an (empty) javadoc jar, which
// is what the portal validates. Credentials and the signing key come from ~/.gradle/gradle.properties
// or ORG_GRADLE_PROJECT_* in CI — never from this repository.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Signed whenever a key is configured, which a Central upload needs; a local publish without one
    // (the wrappers' example apps use mavenLocal) stays unsigned instead of failing.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
}
