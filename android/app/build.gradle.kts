plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 back-stack keys are saved with kotlinx.serialization.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.alvaroedo.skypost"
    compileSdk = 37
    // build.sh installs the SDK packages named here and by compileSdk.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.alvaroedo.skypost"
        minSdk = 26 // Android 8.0: USB host + AudioRecord device routing
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // English (default), Spanish and Valencian; drop the other
        // translations libraries bring.
        localeFilters += listOf("en", "es", "ca")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Reported, but a new upstream release must not break the build.
        informational += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    // Phase 2: the Rust core (core/crates/keryx-ffi, UniFFI) is built with
    // cargo-ndk into src/main/jniLibs/<abi>/libkeryx_ffi.so, and its generated
    // Kotlin bindings go into src/main/java/uniffi/.
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Phase 2:
    // implementation("com.github.mik3y:usb-serial-for-android:<current>")  // CAT over USB serial
    // implementation("net.java.dev.jna:jna:<current>@aar")                  // required by UniFFI bindings
}
