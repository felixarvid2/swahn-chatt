plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "se.familychat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.familychat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // libsignal ships arm64 for phones and x86_64 for emulators (see SECURITY.md)
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        resources {
            // libsignal-client (a transitive dependency of libsignal-android) carries its desktop
            // natives (~400 MB) as Java resources; they must not end up in the APK.
            excludes += setOf("libsignal_jni*", "signal_jni*")
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:crypto"))
    implementation(project(":core:client"))
    // Android builds of the libraries :core:* only compile against.
    implementation(libs.libsignal.android)
    implementation(libs.tink.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
