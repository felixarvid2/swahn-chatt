plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The build machine may only have a newer JDK than 17; emit Java 17 bytecode either way.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
