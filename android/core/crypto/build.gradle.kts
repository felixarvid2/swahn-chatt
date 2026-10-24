plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":core:model"))
    // Provided by the platform: libsignal-android + tink-android in :app, the JVM artifacts in tests.
    compileOnly(libs.libsignal.client)
    compileOnly(libs.tink)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.libsignal.client)
    testImplementation(libs.tink)
}

tasks.test { maxHeapSize = "1g" }
