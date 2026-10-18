plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "es.nvislink"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.nvislink"
        minSdk = 26 // Android 8.0: USB host + AudioRecord device routing
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Phase 2: the Rust core (core/crates/nvl-ffi, UniFFI) is built with
    // cargo-ndk into src/main/jniLibs/<abi>/libnvl_ffi.so, and its generated
    // Kotlin bindings go into src/main/java/uniffi/.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Phase 2:
    // implementation("com.github.mik3y:usb-serial-for-android:<current>")  // CAT over USB serial
    // implementation("net.java.dev.jna:jna:<current>@aar")                  // required by UniFFI bindings
}
