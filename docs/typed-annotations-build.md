# Building with the typed graph API

GraphMaquina uses a typed vocabulary that is **generated before javac runs**. A machine references
generated annotations, enum constants and generated symbol classes; javac checks their types.

That has one consequence the build must respect: **generation is mandatory and it happens before compilation**.
`graphmaquina-codegen` reads the sources, writes the typed vocabulary and an annotation manifest; javac then
compiles the sources together with that vocabulary while `graphmaquina-processor` runs inside it; a completion
step publishes the index of what javac accepted and seals the inventory of the files the compilation owns.

```
generate  ->  prepare-compile  ->  javac (Lombok, then graphmaquina-processor)  ->  complete-compile
```

Both build adapters implement exactly those four steps. Neither works without the other half: the generator
without the processor would compile a vocabulary nobody indexes, and the processor without the generator would
find no manifest and refuse the compilation with SG602.

| Artifact | Coordinates | Role |
| --- | --- | --- |
| Runtime and annotations | `io.github.graphmaquina:graphmaquina-core` | the engine, the markers, the symbol categories |
| Generator | `io.github.graphmaquina:graphmaquina-codegen` | pre-javac generation, run as a separate process by the adapters |
| Annotation processor | `io.github.graphmaquina:graphmaquina-processor` | runs inside javac, emits the facades and publishes the index |
| Gradle adapter | plugin id `io.github.graphmaquina` | the Gradle build integration |
| Maven adapter | `io.github.graphmaquina:graphmaquina-maven-plugin` | the Maven build integration |

Keep the runtime, generator, processor and adapter at the same version: the one the badge in the
[README](../README.md) reports, set once as the `graphmaquinaVersion` Gradle property or the
`graphmaquina.version` Maven property. The processor emits `<Machine>Runtime` and `<Set>Runtime`. Generated
types normalize underscores and nesting dots to PascalCase, preserving internal case and acronyms.
See the [generated naming reference](../README.md#generated-names).

Only `graphmaquina-core` is a dependency of the application. The generator and the processor are build-time only
and never reach the runtime classpath.

For the first machine, follow the [annotation generation walkthrough](../README.md#how-your-flow-annotations-are-generated).
It shows how to generate vocabulary from a minimal interface before writing the flow, or build complete
sources that already reference generated annotations. The generator reads source syntax before javac
type-checks the application; no previously compiled machine is required.

## Preparing a Central release

The version comes from git tags: both builds read `-PgraphmaquinaVersion` when a release passes it and
otherwise derive it from the most recent tag, so no file in the repository states a release version. A
published version is immutable; future publications use fresh `1.0.x` patch versions. `scripts/release.sh`
runs a complete release, the steps below among them; the [README](../README.md#release-to-maven-central)
describes it.

Set `SIGNING_KEY` to the ASCII-armored private release key and `SIGNING_PASSWORD` to its passphrase, then run:

```bash
./gradlew centralBundle
```

This stages the four library/Maven publications plus the Gradle plugin and its marker, then validates the
expected artifacts, POM metadata, signature files and MD5/SHA-1 checksums. Central staging refuses to run
without `SIGNING_KEY`; local and adapter-test repositories still support unsigned development builds.
The ZIP contains only the current release's expected files, excluding repository metadata and older releases.
The command uploads nothing: only once it succeeds is `build/graphmaquina-central-bundle.zip` ready to go to
Central Portal, through the release script or by hand. Portal validation, namespace ownership and availability
of the public signing key remain external requirements; these local structural checks do not replace Portal
validation.

## Where the artifacts come from

GraphMaquina is published to Maven Central under `io.github.graphmaquina`, which is all a consumer needs.
The rest of this section covers the other two cases: testing a build that is not released yet, and serving
every component, including the Gradle implementation and its marker, from an internal repository. The source
repository is private and requires authorized access; the published artifacts are public.
See the [README](../README.md#configure-gradle) for a complete consumer configuration.

To test local changes, publish from an authorized checkout of this repository:

```bash
./gradlew :graphmaquina-core:publishToMavenLocal :graphmaquina-codegen:publishToMavenLocal \
          :graphmaquina-processor:publishToMavenLocal :graphmaquina-maven-plugin:publishToMavenLocal
./gradlew -p graphmaquina-gradle-plugin publishToMavenLocal      # the plugin jar and its marker
```

The second command is a separate invocation because the Gradle adapter is an included build. It installs both
`io.github.graphmaquina:graphmaquina-gradle-plugin` and the marker `io.github.graphmaquina:io.github.graphmaquina.gradle.plugin`, which is
what `plugins { id("io.github.graphmaquina") version … }` resolves. Replace `publishToMavenLocal` with
`publishAllPublicationsTo<Name>Repository` to publish to a file or internal repository instead.

**Gradle** does not read the local Maven repository unless it is declared, and the plugin marker is resolved
through `pluginManagement`, so a consumer's `settings.gradle.kts` needs both halves:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()          // development builds only; omit for public release verification
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.github.graphmaquina") version providers.gradleProperty("graphmaquinaVersion").get()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
```

A `plugins` block in a build script cannot interpolate a version, so the plugin request declares it here,
from the `graphmaquinaVersion` property the consumer sets in `gradle.properties`.

**Maven** consults the local repository first, so artifacts installed with `publishToMavenLocal` need no
declaration at all. A file or internal repository does need one — either in the POM:

```xml
<repositories>
  <repository>
    <id>graphmaquina-local</id>
    <url>file:///…/adapter-test-repository</url>
    <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
  </repository>
</repositories>
<pluginRepositories>
  <pluginRepository>
    <id>graphmaquina-local</id>
    <url>file:///…/adapter-test-repository</url>
    <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
  </pluginRepository>
</pluginRepositories>
```

or, without touching the POM, in an active profile of a `settings.xml` passed with `mvn -s`. Both halves are
needed: `<repositories>` for `graphmaquina-core`, `<pluginRepositories>` for `graphmaquina-maven-plugin`.
A republished release number must be deleted from the local `~/.m2` repository first — `updatePolicy` governs
snapshot lookups, and Maven keeps serving whatever release artifact it already resolved there regardless of it.

## Gradle

```kotlin
plugins {
    java
    id("io.github.graphmaquina")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    val graphmaquinaVersion: String = providers.gradleProperty("graphmaquinaVersion").get()
    implementation("io.github.graphmaquina:graphmaquina-core:$graphmaquinaVersion")

    compileOnly("org.projectlombok:lombok:1.18.48")
    // Lombok first: the GraphMaquina processor reads the accessors Lombok generates on the entities.
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    annotationProcessor("io.github.graphmaquina:graphmaquina-processor:$graphmaquinaVersion")
}
```

That is the whole consumer-side build script, on top of the repositories the previous section declares. The
plugin does not apply the `java` plugin; it reacts to it, so `java` (or any plugin that applies it) must be
applied too.

### Which source sets take part

A source set takes part **when, and only when, its own annotation-processor configuration declares
`io.github.graphmaquina:graphmaquina-processor`** — `annotationProcessor` for `main`, `testAnnotationProcessor` for
`test`, and so on. The processor is what publishes the index of what javac accepted, so a source set compiled
without it could never complete a compilation the plugin prepared.

A source set that does not declare it is left as the `java` plugin configured it: no generated source root, no
annotation manifest, no lifecycle around javac, and no `-Xlint:processing` warning about a processor that
claims none of its annotations. The one exception is `options.isIncremental`, which the plugin switches off for
every compilation of the project — see below. Its generation task still exists, and skips itself with the
declaration it is waiting for as the reason:

```
$ ./gradlew generateTestStateGraphApi --info
Skipping task ':generateTestStateGraphApi' as task onlyIf 'the test source set declares
io.github.graphmaquina:graphmaquina-processor in testAnnotationProcessor' is false.
```

### Tasks and outputs

| Task | Source set |
| --- | --- |
| `generateStateGraphApi` | `main` |
| `generateTestStateGraphApi` | `test` |
| `generate<SourceSet>StateGraphApi` | any other source set |

All of them are in the `graphmaquina` task group.

| Path | Content |
| --- | --- |
| `build/generated/sources/graphmaquina/<sourceSet>` | the generated vocabulary and symbol classes; registered as a source directory of the source set |
| `build/graphmaquina/<sourceSet>/request.json` | the request the adapter wrote for the generator |
| `build/graphmaquina/<sourceSet>/annotation-manifest.json` | the manifest javac is pointed at |
| `build/graphmaquina/<sourceSet>/annotation-manifest.pending.json` | recovery ownership during generation; retained on failure, removed after successful publication |
| `build/graphmaquina/<sourceSet>/owned-outputs.json` | the sealed inventory of everything that compilation owns |
| `build/graphmaquina/<sourceSet>/processor-inventory.json` | what the processor emitted, written during javac and read by the completion step |
| `build/classes/java/<sourceSet>/META-INF/graphmaquina/exports.json` | the published index, packaged into the jar |

The generated directory is added with `sourceSet.java.srcDir(...)`, not handed to `JavaCompile` alone, so an
IDE import, a sources jar and a javadoc task all see it.

### What the plugin does to the compilation

For every participating source set the plugin makes `compileJava`:

- depend on the generation task and compile what it generated;
- receive `-Agraphmaquina.annotationManifest=<work>/annotation-manifest.json` through an argument provider;
- run `prepare-compile` before javac and `complete-compile` after it, and declare
  `build/graphmaquina/<sourceSet>/owned-outputs.json` as an output.

Every one of those is wired lazily: which source sets take part is only known once the build script has been
read, so each piece carries an empty value until then. A build script that configures `compileTestJava` before
the `dependencies` block that declares its processor therefore gets the same build as one that does not.

`options.isIncremental = false` is the exception, because it is not a lazy property: the cycle owns and retires
whole outputs, so javac's own incremental bookkeeping must not compete with it, and the switch is set for
**every** Java compilation of a project that applies the plugin, participating or not.

The annotation-processor path itself is never touched, so Lombok keeps running first, exactly as declared.

The build is compatible with Gradle's configuration cache: nothing the plugin stores on a task holds a
`Project`, a `Configuration` or a source set.

### Toolchain

The generator runs as a separate process under the launcher of the project's Java toolchain, not under the
Gradle daemon's JVM. The toolchain must be a **JDK 21 or later**; below that, or an installation with no
`bin/javac`, the build fails before anything is generated:

```
the StateGraph generator needs a Java 21 toolchain or later, but the one selected for this project is
Java 17 at <installation>. Raise java.toolchain.languageVersion to 21 or later.
```

### Choosing the generator

The `graphmaquinaGenerator` configuration carries the generator's classpath. By default it resolves
`io.github.graphmaquina:graphmaquina-codegen:<graphmaquina.generatorVersion>`, and `generatorVersion` defaults to the
plugin's own version, so nothing needs to be declared. Two cases where it does:

```kotlin
// x.y.z is the version the badge in the README reports.
graphmaquina { generatorVersion = "x.y.z" } // explicit override; keep all components aligned
dependencies { add("graphmaquinaGenerator", project(":graphmaquina-codegen")) }  // inside this repository
```

Declaring any dependency on that configuration replaces the default, which is how `graphmaquina-example` runs
against the sibling project instead of a published artifact.

## Maven

Two plugins are required, and neither works without the other.

The repositories of the previous section are assumed; `graphmaquina-core` and `graphmaquina-maven-plugin` must be
resolvable, which for Central or a locally installed build means nothing further to declare.

```xml
<properties>
  <java.version>21</java.version>
  <!-- the version the badge in the README reports -->
  <graphmaquina.version>x.y.z</graphmaquina.version>
</properties>

<dependencies>
  <dependency>
    <groupId>io.github.graphmaquina</groupId>
    <artifactId>graphmaquina-core</artifactId>
    <version>${graphmaquina.version}</version>
  </dependency>
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <!-- Lombok first: the GraphMaquina processor reads the accessors Lombok generates. -->
          <path>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
          </path>
          <path>
            <groupId>io.github.graphmaquina</groupId>
            <artifactId>graphmaquina-processor</artifactId>
            <version>${graphmaquina.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
      <executions>
        <!-- Each compilation reads the manifest the generation that precedes it wrote. -->
        <execution>
          <id>default-compile</id>
          <configuration>
            <compilerArgs>
              <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/annotation-manifest.json</arg>
            </compilerArgs>
          </configuration>
        </execution>
        <execution>
          <id>default-testCompile</id>
          <configuration>
            <compilerArgs>
              <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/test/annotation-manifest.json</arg>
            </compilerArgs>
          </configuration>
        </execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>io.github.graphmaquina</groupId>
      <artifactId>graphmaquina-maven-plugin</artifactId>
      <version>${graphmaquina.version}</version>
      <executions>
        <execution><id>sg-generate</id><phase>generate-sources</phase><goals><goal>generate</goal></goals></execution>
        <execution><id>sg-prepare</id><phase>process-sources</phase><goals><goal>prepare</goal></goals></execution>
        <execution><id>sg-complete</id><phase>compile</phase><goals><goal>complete</goal></goals></execution>
        <execution><id>sg-generate-test</id><phase>generate-test-sources</phase><goals><goal>generate-test</goal></goals></execution>
        <execution><id>sg-prepare-test</id><phase>process-test-sources</phase><goals><goal>prepare-test</goal></goals></execution>
        <execution><id>sg-complete-test</id><phase>test-compile</phase><goals><goal>complete-test</goal></goals></execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

`<release>` does not have to be written out when a parent already sets it — the Spring Boot parent, for
instance, derives `maven.compiler.release` from `java.version`, and the plugin resolves the setting the way
Maven resolves a parameter. What matters is that the effective release is 21 or later.

### The six goals

| Goal | Phase | What it does |
| --- | --- | --- |
| `generate` | `generate-sources` | checks the compiler configuration, runs the generator, adds `target/generated-sources/graphmaquina` as a compile source root |
| `prepare` | `process-sources` | retires the outputs the previous compilation owned and dates the generated carrier past the newest surviving class file |
| `complete` | `compile` | publishes the index of what javac accepted and seals the inventory |
| `generate-test` | `generate-test-sources` | the same for the test source set, into `target/generated-test-sources/graphmaquina` |
| `prepare-test` | `process-test-sources` | the same for the test source set |
| `complete-test` | `test-compile` | the same for the test source set |

The goal prefix is `graphmaquina`, so a goal can also be invoked directly, for example
`mvn graphmaquina:generate`.

The observed order of a `mvn test-compile`, which the plugin's own suite asserts from real Maven output:

```
graphmaquina:generate (sg-generate)
graphmaquina:prepare (sg-prepare)
resources:resources
compiler:compile (default-compile)
graphmaquina:complete (sg-complete)
graphmaquina:generate-test (sg-generate-test)
graphmaquina:prepare-test (sg-prepare-test)
resources:testResources
compiler:testCompile (default-testCompile)
graphmaquina:complete-test (sg-complete-test)
```

Declaration order in the POM does not matter: Maven injects the lifecycle's `maven-compiler-plugin` before the
plugins a POM adds, and a POM declaration of a lifecycle plugin keeps that injected position.

### Outputs

| Path | Content |
| --- | --- |
| `target/generated-sources/graphmaquina` | the generated vocabulary of the main compilation |
| `target/generated-test-sources/graphmaquina` | the same for the test compilation |
| `target/graphmaquina/main/{request,annotation-manifest,owned-outputs,processor-inventory}.json` | the main cycle's request, manifest, sealed inventory and the processor's own inventory |
| `target/graphmaquina/main/annotation-manifest.pending.json` | the generation's recovery ownership; not a manifest javac may consume |
| `target/graphmaquina/test/…` | the same for the test compilation |
| `target/classes/META-INF/graphmaquina/exports.json` | the published index, packaged into the jar |

### Configuration parameters

| Parameter | Property | Default |
| --- | --- | --- |
| `javaExecutable` | `graphmaquina.javaExecutable` | the selected `jdk` toolchain, and the JVM running Maven only when that is a JDK 21 or later |
| `compilationId` | `graphmaquina.compilationId` | `groupId:artifactId:main`, and `groupId:artifactId:test` for the test source set |

### What the Maven adapter requires, and refuses

Every one of these is checked in `generate-sources`, before anything is retired, and every failure names what
to change:

- **A release of 21 or later.** Resolved from the execution's configuration, the plugin's configuration,
  `maven.compiler.release`, `source`/`target` or their properties. No release at all is a failure too, because
  `maven-compiler-plugin` would then fall back to its own default, which is below 21.
- **The annotation processor on the compiler's processor path.** `<proc>none</proc>`,
  `${maven.compiler.proc}=none` and `-proc:none` are refused, and so is a compilation whose
  `<annotationProcessorPaths>` (or, when it declares none, whose resolved dependencies) does not carry
  `io.github.graphmaquina:graphmaquina-processor`.
- **The manifest argument, per execution.** Every `-Agraphmaquina.annotationManifest=` the two scopes declare
  must name the manifest of that execution's own work directory. A missing or divergent one is refused with the
  exact argument to declare.
- **The compiler and the generator on the same JDK.** A compiler forked at an `<executable>`, or a
  `<jdkToolchain>` that selects a different `java`, is refused.
- **No skipped compilation.** `<skipMain>`, `<skip>`, `maven.main.skip` and `maven.test.skip` are refused:
  the build would retire everything the previous compilation published and compile nothing to replace it.
- **The processor's own generated-sources directory left where GraphMaquina owns it.** A configured
  `<generatedSourcesDirectory>` / `<generatedTestSourcesDirectory>` must match the request's.

## Troubleshooting

| Symptom | Cause and remedy |
| --- | --- |
| `SG602` "…names no manifest at all" | javac ran without `-Agraphmaquina.annotationManifest=…`. In Maven, add the argument to that execution; in Gradle, the source set is not participating — declare `graphmaquina-processor` in its annotation-processor configuration. |
| `SG602` "…does not exist" / stale manifest | the generation step did not run before javac. Never invoke `compiler:compile` on its own; run a lifecycle phase so `graphmaquina:generate` and `graphmaquina:prepare` precede it. |
| `SG601` at a machine | the generator could not resolve a type it needs (the entity, the state enum, a host). The message names the type and the declaration. |
| `SG603` | generated names collide after PascalCase normalization, such as `id` / `_id`. The diagnostic names both source declarations; rename the conflict and its references, then regenerate. |
| `SG609` | unsupported string-based declaration. Use `@GenerateGraphApi` and the [generated typed annotations](../README.md#annotation-and-binding-reference). |
| `package OrdersGraph does not exist` | the generated vocabulary is missing: generation did not run, or the source root was not registered. In Gradle, check that `generateStateGraphApi` ran; in Maven, that `sg-generate` ran in `generate-sources`. |
| "the StateGraph generator needs a Java 21 toolchain or later" | raise the Gradle toolchain to 21+, or point Maven's `graphmaquina.javaExecutable` / `jdk` toolchain at a JDK 21+. |
| "…is not on the `<annotationProcessorPaths>` …" | the processor is missing from the compiler's processor path; declare it after Lombok. |
| "…switches annotation processing off…" | remove `proc=none` / `-proc:none`. |
| "…reads the annotation manifest X, while the StateGraph generator writes Y" | copy the argument the message prints into that execution. |
| "…switches the main compilation off…" | drop `maven.main.skip` / `maven.test.skip` / `<skipMain>`, or drop the GraphMaquina plugin from that build. |
| `complete` fails naming `pending-exports.json` | javac ran but published no index: the processor was not on the path for that compilation. |
| `[INFO] StateGraph completes nothing for <id>` | javac did not run at all in this invocation (for example `mvn generate-sources`). Nothing was retired; this is a no-op, not an error. |

### Repeated builds, renames and deletions

Both adapters retire what the previous compilation owned before the next javac, and publish an index only for
what javac actually accepted, so:

- building twice without changes regenerates nothing and republishes nothing (Gradle reports
  `UP-TO-DATE`; Maven rewrites identical artifacts);
- renaming or deleting a machine, an action, a property or a state removes the generated symbols and facades
  of the old names from the next jar, **without `clean`**;
- deleting the last graph declaration leaves an empty index and a jar with no generated class in it;
- a javac failure leaves the pending inventory behind, seals nothing and publishes no index, so a broken
  compilation can never ship the index of the previous one.

## Limits

- **A watched pair must be generated by the same compilation.** `@GraphPeers` may only name machines this very
  compilation generates an API for, because watch vocabulary is typed with both machines' states; a peer that
  lives in another module or in a dependency jar is refused with SG608, and cross-jar watches are deferred.

## Verification

The evidence behind every claim in this document — commands, test names and counts — is in
`typed-annotations-verification.md`.
