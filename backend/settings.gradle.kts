pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "firstpass"

include(
    "core",
    "github",
    "llm",
    "persistence",
    "webhook-handler",
    "review-worker",
    "api",
    "eval",
    "local-dev",
)
