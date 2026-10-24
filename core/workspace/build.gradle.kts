plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:common-api"))
    api(project(":core:workspace-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<JavaExec>("workspaceAtomicWriteStress") {
    group = "verification"
    description = "Run the opt-in SIGKILL atomic-write stress experiment."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.spectordev9.opencode.core.workspace.impl.WorkspaceAtomicWriteStress")
    val runCount = providers.gradleProperty("workspaceAtomicWriteRuns").orElse("1000")
    doFirst { args(runCount.get()) }
    dependsOn(tasks.testClasses)
}
