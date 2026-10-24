plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:common-api"))
    api(project(":core:security-api"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
