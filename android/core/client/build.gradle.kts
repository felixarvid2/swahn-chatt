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
    api(project(":core:network"))
    api(project(":core:crypto"))
    api(libs.kotlinx.coroutines.core)
    compileOnly(libs.libsignal.client)
    compileOnly(libs.tink)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.libsignal.client)
    testImplementation(libs.tink)
}

tasks.test {
    maxHeapSize = "1g"
    // The end-to-end test reads these; pass them through only when set.
    listOf("FAMILYCHAT_TEST_URL", "FAMILYCHAT_TEST_ADMIN_INVITE", "FAMILYCHAT_TEST_DB").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}
