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
    ":core:diagnostics-api",
    ":core:gateway-api",
    ":core:git-api",
    ":core:runtime-api",
    ":core:security-api",
    ":core:supervisor-api",
    ":core:terminal-api",
    ":core:workspace-api",
)

if (providers.gradleProperty("skipApp").orNull != "true") {
    include(":app")
}
