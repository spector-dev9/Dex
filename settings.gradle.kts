pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "opencode-mobile"

include(
    ":core:bridge-api",
    ":core:common-api",
    ":core:diagnostics",
    ":core:diagnostics-api",
    ":core:gateway-api",
    ":core:git-api",
    ":core:model",
    ":core:opencode-client",
    ":core:runtime",
    ":core:runtime-api",
    ":core:security",
    ":core:security-api",
    ":core:supervisor",
    ":core:supervisor-api",
    ":core:terminal-api",
    ":core:workspace",
    ":core:workspace-api",
)

if (providers.gradleProperty("skipApp").orNull != "true") {
    include(":app")
}

if (providers.gradleProperty("spikeHarness").orNull == "true") {
    include(":spike-harness")
    project(":spike-harness").projectDir = file("tests/spikes/harness")
}
