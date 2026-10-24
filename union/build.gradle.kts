plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    namespace = "dev.useunion"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // The pure-JVM suites never touch android.jar; Robolectric suites get the real thing.
            isReturnDefaultValues = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.process)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.json.schema.validator)
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
