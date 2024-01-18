plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.commit451.translationviewdraghelper.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.commit451.translationviewdraghelper.sample"
        minSdk = 21
        targetSdk = 34
        versionCode = 101
        versionName = "1.0.1"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro", getDefaultProguardFile("proguard-android.txt"))
        }
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-rules.pro", getDefaultProguardFile("proguard-android.txt"))
        }
    }

}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(project(":translationviewdraghelper"))
}

