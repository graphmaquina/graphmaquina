import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

val centralStaging = layout.buildDirectory.dir("central-staging")
val centralModules = listOf("graphmaquina-core", "graphmaquina-codegen", "graphmaquina-processor",
    "graphmaquina-maven-plugin", "graphmaquina-gradle-plugin", "graphmaquina-test")
val centralArtifacts = providers.provider {
    val release = project(":graphmaquina-core").version.toString()
    check(!release.endsWith("-SNAPSHOT") && release != "0.0.0-dev") {
        "Central releases need a resolved version, got '$release'. Create a tag or pass -PgraphmaquinaVersion."
    }
    centralModules.flatMap { module ->
        listOf(".pom", ".jar", "-sources.jar", "-javadoc.jar", ".module").map { suffix ->
            "io/github/graphmaquina/$module/$release/$module-$release$suffix"
        }
    } + listOf("io/github/graphmaquina/io.github.graphmaquina.gradle.plugin/$release/" +
        "io.github.graphmaquina.gradle.plugin-$release.pom")
}
val bundlePaths = centralArtifacts.map { artifacts ->
    artifacts.flatMap { artifact ->
        listOf("", ".md5", ".sha1", ".sha256", ".sha512", ".asc", ".asc.md5", ".asc.sha1",
            ".asc.sha256", ".asc.sha512").map { artifact + it }
    }.toSet()
}

val verifyCentralStaging = tasks.register("verifyCentralStaging") {
    group = "publishing"
    description = "Stages all configured publications and checks their metadata, signatures and checksums"
    dependsOn(centralModules.filter { it != "graphmaquina-gradle-plugin" }.map {
        ":$it:publishAllPublicationsToCentralStagingRepository"
    })
    dependsOn(gradle.includedBuild("graphmaquina-gradle-plugin")
        .task(":publishAllPublicationsToCentralStagingRepository"))
    inputs.dir(centralStaging)
    doLast {
        val root = centralStaging.get().asFile
        val xml = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        centralArtifacts.get().forEach { path ->
            val artifact = root.resolve(path)
            check(artifact.isFile && artifact.length() > 0) { "Missing Central artifact: $path" }
            val signature = root.resolve("$path.asc")
            check(signature.isFile && signature.readText().contains("-----BEGIN PGP SIGNATURE-----")) {
                "Missing PGP signature: $path.asc"
            }
            listOf("md5" to "MD5", "sha1" to "SHA-1").forEach { (extension, algorithm) ->
                val checksum = root.resolve("$path.$extension")
                val expected = MessageDigest.getInstance(algorithm).digest(artifact.readBytes())
                    .joinToString("") { "%02x".format(it) }
                check(checksum.isFile && checksum.readText().trim().equals(expected, ignoreCase = true)) {
                    "Missing or invalid checksum: $path.$extension"
                }
            }
            if (path.endsWith(".pom")) {
                val pom = xml.parse(artifact).documentElement
                val fields = (0 until pom.childNodes.length).map { pom.childNodes.item(it) }
                    .filter { it.textContent.isNotBlank() }.map { it.nodeName }.toSet()
                check(fields.containsAll(listOf("name", "description", "url", "licenses", "developers", "scm"))) {
                    "Incomplete Central POM metadata: $path"
                }
            }
        }
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Builds and validates the signed release bundle for a manual Central Portal upload"
    dependsOn(verifyCentralStaging)
    from(centralStaging) {
        include { it.isDirectory || it.relativePath.pathString in bundlePaths.get() }
    }
    includeEmptyDirs = false
    archiveFileName = "graphmaquina-central-bundle.zip"
    destinationDirectory = layout.buildDirectory
}
