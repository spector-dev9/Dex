plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "io.github.spectordev9.opencode"
version = "0.0.0"

subprojects {
    if (!path.startsWith(":core:")) {
        return@subprojects
    }

    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
}

tasks.register("coreCheck") {
    dependsOn(
        subprojects
            .filter { it.path.startsWith(":core:") }
            .map { "${it.path}:build" },
    )
}
