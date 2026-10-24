plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "se.familychat.app"
    compileSdk = 35


    defaultConfig {
        applicationId = "se.familychat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // libsignal ships arm64 for phones and x86_64 for emulators (see SECURITY.md)
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release builds are signed with a developer-supplied keystore (never committed). Without one the release
        // variant is signed with the debug key so that `assembleRelease` can be verified locally.
        create("release") {
            val path = providers.gradleProperty("familychat.keystore").orNull
            if (path != null) {
                storeFile = file(path)
                storePassword = providers.gradleProperty("familychat.keystorePassword").orNull
                keyAlias = providers.gradleProperty("familychat.keyAlias").orNull
                keyPassword = providers.gradleProperty("familychat.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug builds may talk plain http to localhost / private LAN addresses (see network_security_config).
            buildConfigField("boolean", "ALLOW_INSECURE_LAN", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ALLOW_INSECURE_LAN", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (providers.gradleProperty("familychat.keystore").isPresent) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // libsignal-android requires core library desugaring (java.time etc. on older Android versions)
        isCoreLibraryDesugaringEnabled = true
    }
    // Swedish (default) and English only; drops the other languages' strings that libraries bring along.
    androidResources { localeFilters += listOf("sv", "en") }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // test-only natives of libsignal (fake servers); never needed at runtime
            excludes += setOf("**/libsignal_jni_testing.so")
        }
        resources {
            // libsignal-client (a transitive dependency of libsignal-android) carries its desktop
            // natives (~400 MB) as Java resources; they must not end up in the APK.
            excludes += setOf("libsignal_jni*", "signal_jni*")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Room's exported schemas are assets of the debug variant (never of release), so MigrationTestHelper can open old
    // versions in Robolectric tests and on a device; unit tests read the assets of the variant under test.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
}

// The UnifiedPush connector drags in the JVM build of Tink, which duplicates tink-android's classes.
configurations.all { exclude(group = "com.google.crypto.tink", module = "tink") }

// Unit tests run against the debug variant: the exported Room schemas and the Compose test manifest are debug-only. The
// release variant is checked by assembleRelease (R8 with the real rules) instead.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant -> variant.enableUnitTest = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Qualifiers such as @ApplicationScope on constructor properties apply to the parameter and the field (Dagger needs that).
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:crypto"))
    implementation(project(":core:client"))
    // Android builds of the libraries :core:* only compile against.
    implementation(libs.libsignal.android)
    implementation(libs.tink.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.biometric)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.compose)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.unifiedpush.connector)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.work.testing)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    // The end-to-end test (RoomEngineE2ETest) reads these; pass them through only when set (scripts/run-e2e.sh sets them).
    listOf("FAMILYCHAT_TEST_URL", "FAMILYCHAT_TEST_ADMIN_INVITE", "FAMILYCHAT_TEST_APP_ADMIN_INVITE").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
