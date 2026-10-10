plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.c0ldsheep.sanket"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.c0ldsheep.sanket"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// No third-party runtime libraries: only the Android SDK and our own core module.
dependencies {
    implementation(project(":core"))
}
