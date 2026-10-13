plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvapps.manager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvapps.manager"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the APK is directly installable via adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Don't let strict lint rules block the release build; warnings still appear in reports.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
// No third-party dependencies on purpose: smallest possible APK.
