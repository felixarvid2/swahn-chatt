plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
}

// Static analysis: `./gradlew detekt` (all modules). detekt-formatting brings the ktlint rules, so there is one tool and one config
// (config/detekt/detekt.yml). Findings that existed when it was introduced are in each module's detekt-baseline.xml.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    dependencies { add("detektPlugins", rootProject.libs.detekt.formatting) }
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        parallel = true
        source.setFrom("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin")
    }
}
