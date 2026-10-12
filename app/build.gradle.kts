plugins {
    id("com.android.application")
}

android {
    namespace = "de.herbertwalde.diktat"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.herbertwalde.diktat"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "1.0"
        ndk {
            // Galaxy S26 Ultra ist arm64. Andere ABIs nur, damit der Emulator (x86_64) läuft.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Privat genutzte App: mit dem Debug-Schlüssel signiert, damit Updates per adb ohne Neuinstallation gehen.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
