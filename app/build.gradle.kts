plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ex.dev.sdk.tool.rfidsample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ex.dev.sdk.tool.rfidsample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        // BuildConfig.DEBUG gates the SDK's own logging. Off by default since AGP 8.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // RFID SDK, from the Point Mobile Maven repository declared in settings.gradle.kts.
    implementation("ex.dev.sdk:rfid:1.2.0")

    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}