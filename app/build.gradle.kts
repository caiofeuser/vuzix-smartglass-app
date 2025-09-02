plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.vuzix"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
        mlModelBinding = true
    }

    defaultConfig {
        applicationId = "com.example.vuzix"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation ("com.vuzix:hud-actionmenu:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")


    // Add core Compose dependencies
    implementation(platform("androidx.compose:compose-bom:2024.06.00")) // Use the latest BOM
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.metadata)
    implementation(libs.litert.support.api)
    implementation(libs.tensorflow.lite.gpu)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ... other dependencies like okhttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ADD THIS LINE FOR THE VUZIX SDK
    implementation("com.vuzix:sdk-speechrecognitionservice:1.97.1")
    implementation("com.vuzix:sdk-speechrecognitionservice:1.97.1")
}