pluginManagement { includeBuild("stategraph-gradle-plugin") }

rootProject.name = "stategraph"
include("stategraph-core", "stategraph-processor", "stategraph-codegen", "stategraph-example")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}
