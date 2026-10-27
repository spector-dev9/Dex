plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:common-api"))
    api(project(":core:runtime-api"))
    api(project(":core:supervisor-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
