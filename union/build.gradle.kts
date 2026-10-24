plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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

    publishing {
        singleVariant("release") { withSourcesJar() }
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "dev.useunion"
                artifactId = "union"
                version = sdkVersion
                pom {
                    name.set("Union Android SDK")
                    description.set("Product analytics and crash reporting for Android apps, for the Union platform.")
                    url.set("https://useunion.dev")
                }
            }
        }
    }
}
