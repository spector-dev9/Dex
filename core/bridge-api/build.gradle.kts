dependencies {
    api(project(":core:common-api"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

val compiledContractParity by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(
        "io.github.spectordev9.opencode.core.bridge.CompiledContractParityTestKt",
    )
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
}

tasks.named("check") {
    dependsOn(compiledContractParity)
}
