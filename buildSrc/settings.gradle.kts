// The root build's version catalog, so the build logic's tests use the same JUnit as the modules.
dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
