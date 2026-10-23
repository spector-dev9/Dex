plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        minSdk = 31
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

    sourceSets["main"].assets.srcDir(generatedLicensesDir)
}

kotlin {
    jvmToolchain(21)
}

tasks.named("preBuild").configure {
    dependsOn(syncLicenses)
}
