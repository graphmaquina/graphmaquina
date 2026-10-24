rootProject.name = "graphmaquina-gradle-plugin"
dependencyResolutionManagement {
    repositories { mavenCentral() }
    // The root build's version catalog, so this build compiles with the same Lombok as the sibling modules.
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
