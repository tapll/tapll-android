plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Applied exactly as a customer would; the included build supplies it.
    id("com.tapll")
}

kotlin { jvmToolchain(17) }

android {
    namespace = "com.tapll.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tapll.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The sample is not published: sign with the debug key so `assembleRelease` works anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":tapll"))
    implementation(project(":tapll-compose"))
    implementation(libs.compose.runtime)
}

// The Tapll Gradle plugin: both values below are the defaults, spelled out so the sample documents them.
// The server key (sk_...) belongs to CI; a missing key skips the mapping upload with a warning.
tapll {
    endpoint = providers.gradleProperty("tapll.endpoint").getOrElse("https://in.tapll.com/v1/mappings")
    serverKey = providers.environmentVariable("TAPLL_SERVER_KEY")
}
