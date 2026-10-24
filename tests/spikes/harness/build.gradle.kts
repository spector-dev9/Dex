plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime-assets")
val syncRuntimeManifest = tasks.register<Sync>("syncRuntimeManifest") {
    from(rootProject.file("runtime/manifest.json")) {
        rename { "runtime-manifest.json" }
    }
    into(generatedRuntimeAssets)
}

android {
    namespace = "io.github.spectordev9.opencode.spike"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.spectordev9.opencode.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters += "arm64-v8a"
            }
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedRuntimeAssets)
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

}

dependencies {
    implementation(project(":core:runtime"))
}

tasks.named("preBuild").configure {
    dependsOn(syncRuntimeManifest)
}
