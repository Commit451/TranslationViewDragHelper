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
        versionCode = 100
        versionName = "1.0.0"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(project(":translationviewdraghelper"))
}

