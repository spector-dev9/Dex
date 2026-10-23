plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val generatedLicensesDir = layout.buildDirectory.dir("generated/licenses/assets")
val syncLicenses = tasks.register<Sync>("syncLicenses") {
    from(rootProject.layout.projectDirectory.dir("docs/licenses")) {
        include("NOTICES.md", "SOURCE-OFFER.md", "texts/*")
        into("licenses")
    }
    into(generatedLicensesDir)
}

android {
    namespace = "io.github.spectordev9.opencode"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.spectordev9.opencode"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].assets.srcDir(generatedLicensesDir)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(project(":core:bridge-api"))
    testImplementation(project(":core:common-api"))
    testImplementation(project(":core:diagnostics-api"))
    testImplementation(project(":core:gateway-api"))
    testImplementation(project(":core:git-api"))
    testImplementation(project(":core:runtime-api"))
    testImplementation(project(":core:security-api"))
    testImplementation(project(":core:supervisor-api"))
    testImplementation(project(":core:terminal-api"))
    testImplementation(project(":core:workspace-api"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("preBuild").configure {
    dependsOn(syncLicenses)
}
