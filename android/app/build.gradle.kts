import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "se.swahnchat.app"
    compileSdk = 37
    // The NDK only strips libsignal_jni.so (74 MB -> 7 MB per ABI). Pinned to the version CI installs: AGP 9 would otherwise
    // look for its own default (28.x) and, when that is missing, silently package the unstripped libraries.
    ndkVersion = "27.0.12077973"
    // Which build type the instrumented tests run against: debug (default) or qa (R8-minified, see below). The tasks are
    // connected<Debug|Qa>AndroidTest; AGP only creates the one for this build type, so CI passes -PtestBuildType=qa.
    val testBuild = providers.gradleProperty("testBuildType").getOrElse("debug")
    testBuildType = testBuild

    defaultConfig {
        applicationId = "se.swahnchat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        // libsignal ships arm64 for phones and x86_64 for emulators (see SECURITY.md)
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The R8 (qa) run covers crypto, serialization, database and app start; the Compose UI checks need the debug build.
        if (testBuild == "qa") testInstrumentationRunnerArguments["notPackage"] = "se.swahnchat.app.ui"
    }

    signingConfigs {
        // Release builds are signed with a developer-supplied keystore (never committed). Without one the release
        // variant is signed with the debug key so that `assembleRelease` can be verified locally.
        create("release") {
            val path = providers.gradleProperty("swahnchat.keystore").orNull
            if (path != null) {
                storeFile = file(path)
                storePassword = providers.gradleProperty("swahnchat.keystorePassword").orNull
                keyAlias = providers.gradleProperty("swahnchat.keyAlias").orNull
                keyPassword = providers.gradleProperty("swahnchat.keyPassword").orNull
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
            signingConfig = if (providers.gradleProperty("swahnchat.keystore").isPresent) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // The release build (same R8 rules, same shrinking) that can be installed next to a real release and tested on a
        // device: ./gradlew connectedQaAndroidTest -PtestBuildType=qa. Never published; signed with the debug key.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Only what the test APK itself calls and the app never uses (otherwise R8 removes it from the app before the tests
            // run; AGP's testProguardFiles only reach the test APK). The app rules stay untouched, so a missing app rule fails a test.
            proguardFile("proguard-test-rules.pro")
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
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")
    // ... and of the test APK, which is where MigrationTestHelper looks on a device (needed for the qa build too).
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

// The UnifiedPush connector drags in the JVM build of Tink, which duplicates tink-android's classes.
configurations.all { exclude(group = "com.google.crypto.tink", module = "tink") }

// Unit tests run against the debug variant: the exported Room schemas and the Compose test manifest are debug-only. The
// release variant is checked by assembleRelease (R8 with the real rules) instead.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant -> variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false }
    beforeVariants(selector().withBuildType("qa")) { variant -> variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    implementation(libs.bouncycastle.bcprov)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // Installs the baseline profile (src/main/baseline-prof.txt) from the release and qa APKs when the app is installed.
    implementation(libs.androidx.profileinstaller)
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
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.webrtc.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded) { isTransitive = false } // camera preview only; zxing core is declared above

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

    // AGP's built-in Kotlin does not pick the JUnit variant of kotlin-test by itself (the Kotlin Android plugin used to)
    testImplementation(kotlin("test-junit"))
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

    // On-device tests (src/androidTest): connectedDebugAndroidTest / connectedQaAndroidTest
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    // The end-to-end test (RoomEngineE2ETest) reads these; pass them through only when set (scripts/run-e2e.sh sets them).
    listOf("SWAHNCHAT_TEST_URL", "SWAHNCHAT_TEST_ADMIN_INVITE", "SWAHNCHAT_TEST_APP_ADMIN_INVITE").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
