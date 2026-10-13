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

Keep the runtime, generator, processor and adapter at the same version: the one you installed from a
checkout, set once as the `graphmaquinaVersion` Gradle property or the `graphmaquina.version` Maven property. The processor emits `<Machine>Runtime` and `<Set>Runtime`. Generated
types normalize underscores and nesting dots to PascalCase, preserving internal case and acronyms.
See the [generated naming reference](guide.md#generated-names).

Only `graphmaquina-core` is a dependency of the application. The generator and the processor are build-time only
and never reach the runtime classpath.

For the first machine, follow the [annotation generation walkthrough](guide.md#how-your-flow-annotations-are-generated).
It shows how to generate vocabulary from a minimal interface before writing the flow, or build complete
sources that already reference generated annotations. The generator reads source syntax before javac
type-checks the application; no previously compiled machine is required.

## Preparing a Central release

The version comes from git tags: both builds read `-PgraphmaquinaVersion` when a release passes it and
otherwise derive it with `git describe --tags`, so no file in the repository states a release version. That is
the tag itself on a tagged commit, and on any other commit the most recent tag followed by the commits since it
and the abbreviated commit, as `2.0.0-12-g03952fd`, so a development build never takes the coordinates of a
published release; `centralBundle` refuses such a version. A published version is immutable. The
[guide](guide.md#release-to-maven-central) describes the release, the steps below among them.

Set `SIGNING_KEY` to the ASCII-armored private release key and `SIGNING_PASSWORD` to its passphrase, then run:

```bash
./gradlew centralBundle
```

This stages the five library and Maven publications plus the Gradle plugin and its marker, then validates the
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
See the [guide](guide.md#configure-gradle) for a complete consumer configuration.

To test local changes, publish from an authorized checkout of this repository, under a version string of your
choice, the one the consumer then sets as `graphmaquinaVersion` or `graphmaquina.version`:

```bash
./gradlew -PgraphmaquinaVersion=x.y.z \
          :graphmaquina-core:publishToMavenLocal :graphmaquina-codegen:publishToMavenLocal \
          :graphmaquina-processor:publishToMavenLocal :graphmaquina-maven-plugin:publishToMavenLocal
./gradlew -PgraphmaquinaVersion=x.y.z -p graphmaquina-gradle-plugin publishToMavenLocal  # the plugin jar and its marker
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

The annotation processor uses javac's public `Trees` API to place diagnostics at the exact annotation value,
including nested values in repeated annotations. It does not opt into Gradle incremental annotation processing:
Gradle's incremental processor environment hides the `Trees` API. Builds that use the GraphMaquina Gradle plugin
are unaffected: the plugin already disables incremental Java compilation, because its completion enumerates every
class file the compilation produced, which an incremental recompilation of only some sources would leave
incomplete; normal task up-to-date checks and configuration-cache reuse still apply. The processor is not meant to
run without a GraphMaquina build adapter: without the manifest its generation step writes, typed declarations are
refused with `SG602`.

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
claims none of its annotations. The one exception is `options.isIncremental`, which the plugin switches off on
the compile task of every source set — see below. Its generation task still exists, and skips itself with the
declaration it is waiting for as the reason:

```
$ ./gradlew generateTestStateGraphApi --info
Skipping task ':generateTestStateGraphApi' as task onlyIf 'the test source set declares
io.github.graphmaquina:graphmaquina-processor in testAnnotationProcessor' is false.
```

The declarations are counted as the build adds and removes them, so one the build removes again no longer
counts, and the answer is settled when the task graph is built. A processor that a `defaultDependencies` or
`withDependencies` action adds only once javac's processor path is resolved does not count: javac then runs the
processor without a manifest, and a compilation that declares graphs is refused with SG602.

### Tasks and outputs

| Task | Source set |
| --- | --- |
| `generateStateGraphApi` | `main` |
| `generateTestStateGraphApi` | `test` |
| `generate<SourceSet>StateGraphApi` | any other source set |

All of them are in the `graphmaquina` task group.

| Path | Content |
| --- | --- |
| `build/generated/sources/graphmaquina/<sourceSet>` | the generated vocabulary and symbol classes; added to the java sources of the source set as a source directory set of its own |
| `build/graphmaquina/<sourceSet>/request.json` | the request the adapter wrote for the generator |
| `build/graphmaquina/<sourceSet>/annotation-manifest.json` | the manifest javac is pointed at |
| `build/graphmaquina/<sourceSet>/annotation-manifest.pending.json` | recovery ownership during generation; retained on failure, removed after successful publication |
| `build/graphmaquina/<sourceSet>/owned-outputs.json` | the sealed inventory of everything that compilation owns |
| `build/graphmaquina/<sourceSet>/processor-inventory.json` | what the processor emitted, written during javac and read by the completion step |
| `build/classes/java/<sourceSet>/META-INF/graphmaquina/exports.json` | the published index, packaged into the jar |

The generated directory joins `sourceSet.java` as a source directory set of its own, not handed to
`JavaCompile` alone, so an IDE import, a sources jar and a javadoc task all see it. The source set's include and
exclude patterns filter the original sources only, so javac compiles everything the generation wrote. That
includes the compilation carrier, `io/github/graphmaquina/generated/bootstrap/GraphCompilation<digest>.java`,
which is tool-internal: `complete-compile` removes its class file, so the jar never carries it, and the plugin
leaves its source out of the source set's sources jar and javadoc.

### What the generator reads

The generation task parses what javac compiles of the source set: every source directory but the generated
ones, file by file as Gradle selects them through the include and exclude patterns of the source set's java
sources and their filter, `java.filter`, the compile task's include and exclude pattern strings, and the build's
default excludes. A directory is handed to the generator whole when the generator's own walk of it selects the
same files: when neither the source set nor the compile task gives it a pattern, `java.filter` is the `java`
plugin's own `**/*.java` include, and the default excludes are Ant's, which Gradle applies unless a settings
script changes them through `org.apache.tools.ant.DirectoryScanner`. Files the default excludes leave out, such
as `._*` copies, are left out either way, and so is every directory one of them names, `.git` or `old~` for
instance, with everything inside it. Symbolic links are followed, a link that leads back into a directory being
walked skipped.

A filter given to the compile task as a spec or a closure, and a source added to the compile task or put in place
of the source set's, are not followed. When they make javac compile other original Java sources than the
generation reads, the generation task refuses the build before the generator runs, and `prepare-compile` does
when the generation is up to date. Give such a filter to the source set's java sources, where a spec or a closure
is followed, or to the compile task as an include or exclude pattern. For a source added to the compile task, the
refusal names the directory to give the source set with `java.srcDir(...)`; for a source put in place of the
source set's, it prints a line that gives the source set the directories the compile task compiles, followed by
the line that adds the generated directory back: `java.setSrcDirs(listOf<String>(…)); …` for the Kotlin DSL and
`java.srcDirs = […]; …` for the Groovy DSL.

The task runs after every task those directories are built by, so a directory registered with the task that
writes it, as `java.srcDir(generateOther)` registers the output of a `generateOther` task, is written before it
is read. A directory added by path alone carries no such task, so nothing orders its writer before the
generation: depending on the order the two tasks run in, the build stops with Gradle's own error that the
generation task uses an output of that task without declaring a dependency on it, or the generation reads the
directory before it is written. Register such a directory with the task that writes it.

The sources are parsed at the release javac compiles at: the value of the last `--release` of
`options.compilerArgs`, spelled `--release N` or `--release=N`, when it is a plain decimal number, else
`options.release`, else the language version of the compilation's toolchain. They are read in the encoding
javac reads them in: with Gradle's own compiler, in process or in its compiler daemon, `options.encoding`, else
the last `-encoding` of `options.compilerArgs`; with a javac Gradle starts from the command line, as it does
when `options.fork` is set with `forkOptions.javaHome` or `forkOptions.executable`, the last `-encoding`, else
`options.encoding`. The generated sources are written in that encoding, a character it cannot represent as a
`\uXXXX` escape; without an encoding, the generator reads and writes UTF-8. The companions the annotation
processor generates hold ASCII only: javac writes them in that encoding too, so the processor writes every other
character, of a name or of a text such as a policy's `otherwise`, as a `\uXXXX` escape. The generation never runs
the compile task's argument providers, which may need files that other tasks write first; `prepare-compile`
refuses providers that change the release or the encoding.

### What the plugin does to the compilation

For every participating source set the plugin makes `compileJava`:

- depend on the generation task and compile what it generated;
- receive `-Agraphmaquina.annotationManifest=<work>/annotation-manifest.json` through an argument provider;
- run `prepare-compile` before javac and `complete-compile` after it, and declare
  `build/graphmaquina/<sourceSet>/owned-outputs.json` as an output.

Every one of those is wired lazily: which source sets take part is only known once the build script has been
read, so each piece carries an empty value until then. A build script that configures `compileTestJava` before
the `dependencies` block that declares its processor therefore gets the same build as one that does not.

`options.isIncremental = false` is the exception. The completion enumerates the class files the compilation
produced for the units it owns, which an incremental recompilation of only some sources would leave incomplete,
and the switch is not a lazy property, so it is set on the compile task of **every** source set, participating or
not, and on no other compilation of the project.

The annotation-processor path itself is never touched, so Lombok keeps running first, exactly as declared.

The build is compatible with Gradle's configuration cache: nothing the plugin stores on a task holds a
`Project`, a `Configuration` or a source set.

Before it retires anything, `prepare-compile` refuses a compilation that could end without the classes the
completion publishes, that would run without the StateGraph annotation processor, which writes the provisional
index the completion publishes, whose class files would lack the `SourceFile` attribute the completion reads,
that would read the sources annotation processors generate in another charset than javac writes them in, or
that would not compile everything generated and exactly the original sources the generation read, at its release
and in its encoding:

- `options.failOnError = false`;
- `-proc:only` or `-proc:none` as the last `-proc:` option among the compiler arguments, the one javac takes;
- an `options.annotationProcessorPath` that is empty or null. Gradle then gives javac a `-proc:none` of its own,
  and its own compiler, in process or in its compiler daemon, runs only the processors of that path, whatever
  `-proc:` compiler argument follows. Only a javac Gradle starts from the command line is left to run with such a
  path, when a `-proc:` compiler argument follows, since that javac then looks for processors on the class path;
- a `-processor` list, the last one among the compiler arguments, that leaves out
  `io.github.graphmaquina.processor.StateGraphProcessor` or holds a name that is not a valid qualified class
  name, such as an empty one or one with spaces around it. The Java compiler of Gradle 9.2.1, in process or in
  its compiler daemon, keeps empty names when it splits the list on its commas, so an empty name is refused
  wherever it stands, after the last comma included;
- a debug setting that leaves the `SourceFile` attribute out of the class files: `options.debug = false`, an
  `options.debugOptions.debugLevel` without `source`, or `-g:none` or a `-g:` list without `source` among the
  compiler arguments, unless another `-g:` list names `source`;
- with Gradle's own compiler, in process or in its compiler daemon, a last `-encoding` compiler argument that
  names another charset than `options.encoding`, when this JVM supports both. That compiler reads the sources in
  `options.encoding`, while javac writes the sources annotation processors generate in the last `-encoding`; an
  alias of the same charset passes;
- argument providers that change the release or the encoding javac takes from those the generation read. Give
  `--release` or `-encoding` in `options.compilerArgs`, or set `options.release` or `options.encoding`, instead;
- a compilation whose original Java sources are not those the generation read, as
  [above](#what-the-generator-reads);
- a compilation whose sources leave out a generated file, as replacing the source set's java directories with
  `java.setSrcDirs(...)` does, or a source or a pattern set on the compile task itself. The refusal prints, for
  the Kotlin and the Groovy DSL, the line that adds the generated directory back after a replacement, and asks
  for a setting on the compile task to be removed.

A processor path without the StateGraph processor, a well-formed name of a class that is on no processor path,
such as a misspelt one, and a `-g:` option javac rejects are not refused: the compilation fails on them after
the preparation, like any failed compilation, and the next preparation cleans up after it from the pending
inventory it left.

That the plugin retires nothing does not mean the previous outputs survive a refusal. Adding
`options.failOnError = false` or a compiler argument, such as `-proc:none`, changes the compile task's options,
and Gradle then runs the task as a full rebuild: before the preparation runs, Gradle itself removes the task's
previous outputs, the classes, the published index and the sealed inventory among them. A refusal leaves no
pending inventory behind.

The generation task's `sourcesOutput`, `workDirectory`, `requestFile`, `javaLauncher`, `generatorClasspath`,
`release`, `encoding` and `compilationId` are conventions: a value a build sets wins whenever it sets it, and
the compilation follows what the task holds, from the source root, the manifest and the sealed inventory to the
launcher and the classpath `prepare-compile` and `complete-compile` run with. Paths a build adds to
`generatorClasspath` join the default only when they are added after the plugin configured the task; added
before, they replace it. `classesOutput` and `processorSourcesOutput` follow the compile task, and
`annotationManifest` follows the work directory: a build that sets any of the three is refused.

### Toolchain

The generator runs as a separate process under the generation task's `javaLauncher`, not under the Gradle
daemon's JVM; `prepare-compile` and `complete-compile` run under the same launcher. By convention it is the `java`
of the JDK javac compiles with, so the generator and javac run on one JDK and the generator parses at a release
its JDK supports: the JDK of the compile task's `javaCompiler`, which is a compiler the build gives the compile
task, else the JDK of `options.forkOptions.javaHome` or `options.forkOptions.executable` when `options.fork` is
set, else the project's Java toolchain. A `javaLauncher` the build sets wins, but one whose JDK is older than the
release the sources are parsed at is refused before the generator runs, since a JDK supports no release later
than its own:

```
the StateGraph generator cannot parse the sources of task ':generateStateGraphApi' at release 25 under its
javaLauncher, Java 21 at <installation>, since a JDK supports no release later than its own. Give javaLauncher
a JDK 25 or later. A build that does not set it runs the generator on the JDK javac compiles with, which must
support that release as well.
```

That JDK must be a **JDK 21 or later**; below that, or an installation with no `bin/javac`, the build fails
before anything is generated:

```
the StateGraph generator needs a Java 21 toolchain or later, but the one it runs under, the JDK javac compiles
with unless the generation task's javaLauncher names another, is Java 17 at <installation>. Raise
java.toolchain.languageVersion, the toolchain of the compile task or that javaLauncher, whichever selects it,
to 21 or later.
```

### Choosing the generator

The `graphmaquinaGenerator` configuration carries the generator's classpath. By default it resolves
`io.github.graphmaquina:graphmaquina-codegen:<graphmaquina.generatorVersion>`, and `generatorVersion` defaults to the
plugin's own version, so nothing needs to be declared. Two cases where it does:

```kotlin
// x.y.z is the version you installed from a checkout.
graphmaquina { generatorVersion = "x.y.z" } // explicit override; keep all components aligned
dependencies { add("graphmaquinaGenerator", project(":graphmaquina-codegen")) }  // inside this repository
```

Declaring any dependency on that configuration replaces the default, which is how `graphmaquina-example` runs
against the sibling project instead of a published artifact. Each generation task takes that configuration as
the convention of its `generatorClasspath`; a classpath set on the task wins, and the `prepare-compile` and
`complete-compile` of its source set run over it too. What a generator run that completes writes on its error
stream is logged as a warning, as the Maven plugin logs it.

## Maven

Two plugins are required, and neither works without the other.

The repositories of the previous section are assumed; `graphmaquina-core` and `graphmaquina-maven-plugin` must be
resolvable, which for Central or a locally installed build means nothing further to declare.

```xml
<properties>
  <java.version>21</java.version>
  <!-- the version you installed from a checkout -->
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
Maven resolves a parameter. What matters is that the effective release is 21 or later. The test compilation
reads `<testRelease>`, `<testSource>` and `<testTarget>`, or their `maven.compiler.test*` properties, in place of
`<release>`, `<source>` and `<target>` wherever they are set, as `maven-compiler-plugin` does.

### The six goals

| Goal | Phase | What it does |
| --- | --- | --- |
| `generate` | `generate-sources` | checks the compiler configuration, runs the generator, adds `target/generated-sources/graphmaquina` as a compile source root |
| `prepare` | `process-sources` | retires the outputs the previous compilation owned, the published index among them, and dates the generated carrier past the newest file the class output held before that retirement; `maven-compiler-plugin` 3.15.0 recompiles without that date, since the carrier has no class file by then |
| `complete` | `compile` | publishes the index of what javac accepted and seals the inventory |
| `generate-test` | `generate-test-sources` | the same for the test source set, into `target/generated-test-sources/graphmaquina` |
| `prepare-test` | `process-test-sources` | the same for the test source set |
| `complete-test` | `test-compile` | the same for the test source set |

The goal prefix is `graphmaquina`, so a goal can also be invoked directly, for example
`mvn graphmaquina:generate`.

A goal does nothing, and says so at info level (`StateGraph skips <compilationId>: …`), for a compilation the build
switches off the way `maven-compiler-plugin` skips its own goal: `maven.main.skip` or `<skipMain>` for the three
main goals, `maven.test.skip` or `<skip>` for the three test goals. So does it for a compilation the default
lifecycle runs no execution of the compiler's goal for, as when `default-compile` is bound to the phase `none`, or
to a phase of another lifecycle such as `pre-site`, and nothing else compiles the main sources. Nothing is
generated, retired or published then. All six do nothing in a project packaged as `pom`, such as the parent of a
multi-module build whose modules inherit the executions.

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

Declaration order in the POM does not matter for `default-compile` and `default-testCompile`: Maven runs the
executions the lifecycle binds before those a POM adds to the same phase. An execution of the compiler a POM adds
itself, as below, runs among the others of its phase in the order their plugins are declared, so declare
`maven-compiler-plugin` before `graphmaquina-maven-plugin`: otherwise `complete` would run before that javac, and
`generate` refuses the build before anything is retired, naming both executions.

### The compiler execution the goals read

The checks and the request read the execution of the `compile` goal of `maven-compiler-plugin`, `testCompile` for
the test goals, that the default lifecycle runs and that compiles the vocabulary the generator writes, whatever its
id. An execution runs there when it is bound to a phase of the default lifecycle, the one the StateGraph goals run
in: the phase it declares, else the default phase of its goal, `compile` or `test-compile`. One bound to anything
else is never read: one bound to `none` never runs, and one bound to a phase of another lifecycle, such as
`pre-site` or `clean`, runs only when the build invokes that lifecycle, never between the preparation and the
completion. So a build may bind `default-compile` and `default-testCompile` to `none` and compile its Java sources
through executions of its own, as `kotlin-maven-plugin` asks a mixed Kotlin and Java build to do; the manifest
argument and every setting the adapter checks then belong to those executions. An execution compiles the vocabulary
unless its own `<compileSourceRoots>` leave out the directory the generator writes into; one that compiles another
source root into a multi-release jar, with `<multiReleaseOutput>` and roots of its own, runs untouched beside the
main compilation. When no execution of the goal runs, the compilation is skipped, as above. Two running executions
that both compile the vocabulary are refused, naming both: javac would compile it twice into one class output, and
the later run fails on the classes of the earlier one.

Every setting the goals read from that execution, or from the property the setting is bound to, is read as Maven
evaluates it for the compiler, through Maven's own evaluator of plugin parameters. An expression the effective model
leaves for that evaluator, such as `${session.executionRootDirectory}` or `${settings.localRepository}`, is completed
first, so an `<outputDirectory>` or `<compileSourceRoots>` spelled with one is checked, and passed to the generator,
as the compiler gets it. A value that is one expression naming an object Maven does not take for that setting, such
as `${project.file}` for a text, counts as unset, and an expression that cannot be completed yet stays as it is
written, as the [compiler arguments](#what-the-maven-adapter-requires-and-refuses) do.

### Outputs

| Path | Content |
| --- | --- |
| `target/generated-sources/graphmaquina` | the generated vocabulary of the main compilation |
| `target/generated-test-sources/graphmaquina` | the same for the test compilation |
| `target/graphmaquina/main/{request,annotation-manifest,owned-outputs,processor-inventory}.json` | the main cycle's request, manifest, sealed inventory and the processor's own inventory |
| `target/graphmaquina/main/annotation-manifest.pending.json` | the generation's recovery ownership; not a manifest javac may consume |
| `target/graphmaquina/test/…` | the same for the test compilation |
| `target/classes/META-INF/graphmaquina/exports.json` | the published index, packaged into the jar |

The compilation carrier, `io/github/graphmaquina/generated/bootstrap/GraphCompilation<digest>.java`, is
tool-internal: `complete` removes its class file, so the jar never carries it. This plugin configures no other
plugin, and `generate` adds `target/generated-sources/graphmaquina` to the compile source roots, which
`maven-source-plugin` and `maven-javadoc-plugin` read: the carrier's source lands in the sources jar and its page
in the javadoc unless their configuration leaves it out. In the `<configuration>` of `maven-javadoc-plugin`
3.12.0, either of these settings leaves it out, except that `sourceFileExcludes` is ignored when `<subpackages>`
is set:

```xml
<sourceFileExcludes>
  <sourceFileExclude>io/github/graphmaquina/generated/bootstrap/**</sourceFileExclude>
</sourceFileExcludes>
<!-- or -->
<excludePackageNames>io.github.graphmaquina.generated.bootstrap</excludePackageNames>
```

In the `<configuration>` of `maven-source-plugin` 3.4.0, this one does:

```xml
<excludes>
  <exclude>io/github/graphmaquina/generated/bootstrap/**</exclude>
</excludes>
```

### Sources, release and encoding

The source roots are the compiler's own `<compileSourceRoots>` when the execution declares them, a relative one
resolved against the directory Maven was started in, as `maven-compiler-plugin` resolves it, and the project's
otherwise; an empty `<compileSourceRoots/>` stands for the project's. A source root the execution does not narrow
is passed to the generator whole, but for the case below; one its `<includes>` or `<excludes>` narrow
(`<testIncludes>`, `<testExcludes>` or `<testIncrementalExcludes>` for the tests) is passed as the files they
select, with the patterns matched as the compiler's plexus-utils scanner matches them on the platform the build
runs on, `%ant[...]` and `%regex[...]` included, and without the files that scanner excludes by default, the list of
plexus-utils, which leaves out `._*` copies and the directories of version control systems among others. That
scanner spells a path with the file
separator of the platform, `/` or `\`, and rewrites the separators of a `%regex[...]` pattern for it: every pair of
backslashes becomes `/` where the separator is `/`, and every `/` becomes a pair of backslashes, an escaped
backslash, where it is `\`. So `/` and an escaped backslash stand for the separator on either platform, while an
expression that escapes a `/`, such as `fx\/drafts\/.*`, selects the drafts only where the separator is `/`.
Directories are entered as that scanner enters them: only when an include matches the directory and no exclude
does, or when an include could match a path below it. Where the separator is `/`, an include that starts with a
separator never can: it matches a path as if it had no leading separator, so `/fx/**` selects the sources under
`fx`, a directory it matches, while `/io/github/graphmaquina/generated/**` alone selects nothing, since the
scanner never enters `io`. Where the separator is `\`, as on Windows, plexus-utils also lets the scanner in for
such an include as if it had no leading separator, so `/io/github/graphmaquina/generated/**` selects the sources
under `io/github/graphmaquina/generated`. A directory that cannot be listed is read as an empty one, as that scanner
reads it.

The generator walks a whole root as Gradle walks a source directory, with Ant's shorter default list: it leaves
out every directory a default exclude names, with everything inside it. The compiler's scanner leaves out what is
inside a directory only when an exclude ending in `/**` names it, as one names each directory of a version control
system, so a root under which javac compiles a source inside a directory named like an excluded file, `old~` or
`.gitignore` for instance, is passed as the files the compiler selects, as a narrowed one is. So is a root that holds
a directory the generator's walk would open but cannot list: the walk would fail on it, while the compiler's scanner,
and so the files passed in its place, read it as an empty one. Under a root passed whole, a `.java` file inside an
`RCS`, `BitKeeper`, `ChangeSet`, `_darcs`, `.metadata`, `.arch-ids` or `.darcsrepo` directory, or one named
`-darcs-backup*`, is read by the generator although javac never compiles it. Symbolic links are followed, and a
link that leads back into a directory being walked is skipped.

The request also carries the release javac compiles at, `<release>`, else `<target>`, or their properties, with the
test settings for the test compilation. javac takes the last `--release` it is given, and `maven-compiler-plugin`
passes a release that is set ahead of the compiler arguments, so the last `--release` compiler argument, spelled
`--release N` or `--release=N`, wins over that release; without a release, the compiler passes `-source` and
`-target`, which javac refuses beside a `--release`. The request also carries the encoding javac reads the sources
in. The configured encoding is `<encoding>`, else `project.build.sourceEncoding`. A javac that compiles inside Maven
through the `javax.tools` API, as it does by default, reads the sources through a file manager
`maven-compiler-plugin` opens in the configured encoding, which wins over every `-encoding` compiler argument: the
encoding is the configured one, else the last `-encoding` compiler argument. A javac the compiler forks, as it does
when `<fork>` is true and when it compiles with a `jdk` toolchain, or runs inside Maven through
`com.sun.tools.javac.Main`, as it does when `<forceLegacyJavacApi>`, `<forceJavacCompilerUse>` or their
`maven.compiler.*` property is true, gets the configured encoding as an `-encoding` ahead of the compiler arguments
and takes the last one: the encoding is the last `-encoding` compiler argument, else the configured one. The
generator parses at that release, reads in that encoding and writes the generated sources in it; without an
encoding, it reads and writes UTF-8. The companions the annotation processor generates hold ASCII only, every other
character written as a `\uXXXX` escape, so javac writes them alike in any encoding. A javac that compiles inside
Maven through the `javax.tools` API still writes them in the last `-encoding` compiler argument and reads them back
in the configured encoding, so `generate` refuses an `-encoding` compiler argument that names another charset than
the configured one, as listed below.

### Configuration parameters

| Parameter | Property | Default |
| --- | --- | --- |
| `javaExecutable` | `graphmaquina.javaExecutable` | the `java` of the `jdk` toolchain selected for the build, else the `java` of the JVM running Maven; whichever it is must belong to a JDK 21 or later, the one javac compiles with |
| `compilationId` | `graphmaquina.compilationId` | `groupId:artifactId:main`, and `groupId:artifactId:test` for the test source set |

### What the Maven adapter requires, and refuses

Every one of these is checked by `generate`, and by `generate-test` for the test compilation, before anything is
written or retired, on the compiler execution [the goals read](#the-compiler-execution-the-goals-read), and every
failure names what to change:

- **One execution compiling the vocabulary.** Two running executions of the compiler's goal that both compile the
  directory the generator writes into are refused, naming both.
- **javac between `prepare` and `complete`.** The compiling execution must run after every execution of `prepare`
  and before every execution of `complete` (`prepare-test` and `complete-test` for the tests), in the order Maven
  runs them: phase by phase, and within a phase the executions the lifecycle of the packaging binds, such as
  `default-compile`, first, then the others in the order their plugins are declared. An execution of the compiler
  a POM adds itself and binds to the phase of `complete` is refused when `graphmaquina-maven-plugin` is declared
  first, and one bound to a phase that runs before `prepare`, or to its phase with `maven-compiler-plugin` declared
  first, is refused too: the completion would find nothing javac compiled, after the preparation retired the
  previous outputs.

- **A release of 21 or later.** Resolved from the execution's configuration, the plugin's configuration,
  `maven.compiler.release`, `source`/`target` or their properties, a property given on the command line winning
  over one of the JVM, and that over one of the project. The test compilation is checked against its own test
  settings when they are set. When a release is set, the last `--release` compiler argument wins over it, as it
  does for javac, and is the one checked; the refusal then asks for that argument to change. No release at all is
  a failure too, because `maven-compiler-plugin` would then fall back to its own default, which is below 21.
- **The annotation processor on the compiler's processor path.** Annotation processing switched off is refused:
  `-proc:none` as the last `-proc:` option javac gets, from `<proc>none</proc>`, `${maven.compiler.proc}=none`
  or a compiler argument. `maven-compiler-plugin` passes `<proc>` ahead of the compiler arguments, so a later
  `-proc:full` argument wins over it. Given a processor path, javac looks for processors there and not on the
  class path, so the processor must be where javac looks:
  - a `-processorpath` or `--processor-path` compiler argument, the last one given, must reach the processor's
    class through one of its entries, resolved where javac runs, the jars the `Class-Path` of a jar's manifest
    names, or the versioned entries a multi-release jar holds for the release the `release` file of javac's JDK
    declares, any of them when that file declares none. The argument is evaluated first, as the compiler arguments
    are (below): a value that evaluates to nothing is left out, so javac takes the argument after it as the path,
    and a value that still holds `${` after that, such as a property that a plugin bound to a later phase sets, is
    not checked;
  - otherwise `<annotationProcessorPaths>`, when it holds a path, must name
    `io.github.graphmaquina:graphmaquina-processor`; an empty `<annotationProcessorPaths/>` counts as absent, as
    it does for the compiler;
  - otherwise the resolved dependencies must carry the processor. javac 23 and later runs a processor found on
    the class path only when processing is requested, by `-proc:full` or `-proc:only` as the last `-proc:`
    option or by a `-processor` list, so with a javac whose JDK declares release 23 or later in its `release`
    file, a processor that is only a dependency is refused unless processing is requested.

  A `--processor-module-path` compiler argument is refused: javac rejects one beside a processor path, and
  without a processor path it looks for processors on that module path instead of the class path.
- **A processor list that names the processor.** Given a `-processor` list, javac runs only the processors it
  names, and of several lists it takes the last. The list javac gets is the last `-processor` compiler argument,
  else `<annotationProcessors>`, which `maven-compiler-plugin` passes as such a list ahead of the compiler
  arguments, even when it names nothing. A list that leaves out
  `io.github.graphmaquina.processor.StateGraphProcessor` is refused, and so is one holding a name that is not a
  valid qualified class name, which javac cannot load: an empty or blank one, one with spaces around it, or a
  value of `<annotationProcessors>` left empty, which reaches javac as `null`. javac splits the list on every
  comma and drops the empty names at its end, though an empty list is one empty name, and the check splits it
  the same way.
- **A compilation that writes its classes.** `-proc:only` as the last `-proc:` option, from
  `<proc>only</proc>`, `${maven.compiler.proc}=only` or a compiler argument, is refused, since javac would
  compile nothing, and so is `failOnError` set to false, since the build would go on past a compilation javac
  rejected: either way the preparation would have retired the previous outputs and the completion would find
  nothing to publish in their place.
- **Class files with their `SourceFile` attribute, where the completion reads them.** javac leaves the attribute
  out once it is given a `-g:` option, `-g:none` included, and none that lists `source`, whatever their order.
  `maven-compiler-plugin` passes `<debuglevel>`, or `maven.compiler.debuglevel`, as such an option while
  `<debug>` is on, as it is by default, so a `<debuglevel>` or `-g:` compiler arguments that leave `source` out
  are refused; `<debug>false</debug>` passes no `-g` option and is accepted, and a `-g:` option javac cannot read
  is left to javac. The classes must also go to the project's class output: an `<outputDirectory>` (for the main
  compilation also `maven.compiler.outputDirectory`) that names another directory, `<multiReleaseOutput>true`,
  and a last `-d` compiler argument that names another directory, resolved where javac runs, are refused; move the
  class output with `<outputDirectory>` or `<testOutputDirectory>` in the `<build>` of the POM instead.
- **Source roots that hold the generated directory.** `<compileSourceRoots>` that leave out the directory the
  generator writes into are refused, since they replace the project's source roots for javac.
- **A compilation of the whole module.** `useIncrementalCompilation` set to false, by
  `<useIncrementalCompilation>false</useIncrementalCompilation>` or
  `maven.compiler.useIncrementalCompilation=false`, is refused. `maven-compiler-plugin` would then hand javac
  only the sources whose class file is missing or older than the source, so over a previous build javac would
  compile the generated vocabulary without the unchanged hand-written sources, and the annotation processor would
  miss declarations the index publishes. At its default, `true`, the compiler recompiles the whole module
  whenever it compiles at all.
- **Includes and excludes that keep the carrier.** The compiler applies its `<includes>` and `<excludes>`
  (`<testIncludes>`, `<testExcludes>` and `<testIncrementalExcludes>` for the tests) to the generated source root
  too. Patterns that leave out the compilation carrier are refused, naming the exclude, or the include list; with
  includes, the remedy is `<include>io/github/graphmaquina/generated/**</include>`. An include that matches the
  carrier leaves it out all the same when the compiler's scanner never enters a directory above it, as
  `<include>/io/github/graphmaquina/generated/**</include>` does where the file separator is `/`, whose leading
  separator keeps the scanner out of `io` there; the refusal then also names that include and that directory. Where
  the separator is `\`, as on Windows, the scanner enters `io` for that include, and it is accepted. A main
  `<incrementalExcludes>` pattern that matches the carrier is refused as well: the compiler leaves what it matches
  out of its check for stale sources, and the carrier, which has no class file once a compilation completes, is what
  makes that check compile the module again after the preparation. Includes that hold only empty values, such as
  `<includes>,</includes>` or a lone `<include/>`, are refused too: Maven turns those values into null,
  `maven-compiler-plugin` then adds no default include, and the compiler would select no source at all.
- **The manifest argument, per execution.** Every `-Agraphmaquina.annotationManifest=` the two scopes declare
  must name the manifest of that execution's own work directory. A relative one is resolved where javac runs:
  against the directory Maven was started in when javac compiles inside Maven, so a module of a build started
  from its parent does not find it, and against the project directory when the compiler forks javac. A missing
  or divergent one is refused with the exact argument to declare.
- **The compiler and the generator on the same JDK.** javac's JDK is resolved as `maven-compiler-plugin`
  resolves it: the `<executable>` it forks; else, unless an `<executable>` configured without a fork makes it
  ignore toolchains, a `jdk` toolchain, the first one its `<jdkToolchain>` matches, the first of the build for an
  empty `<jdkToolchain/>`, or else the one selected for the build; else the JDK running Maven. Its home must be
  the home of the JDK the generator's `java` belongs to, the directory above the `bin` holding that `java` once
  symbolic links are followed. A launcher outside a JDK's `bin` directory, such as a wrapper script, has a home
  of its own and is refused, even when it starts the same JDK. A `<fork>` with neither an `<executable>` nor a
  toolchain makes the compiler look javac up itself, relative to the home of the JDK running Maven: at
  `bin/javac` on macOS, at `../sh/javac` on AIX and at `../bin/javac` elsewhere; then at `$JAVA_HOME/bin/javac`;
  and last as a plain `javac` from the `PATH`. The check still takes the JDK running Maven for javac's then. That
  is the forked javac's JDK when javac is found in that JDK's own `bin`, which only the macOS lookup tries, or at
  `$JAVA_HOME/bin/javac` with `JAVA_HOME` naming the JDK running Maven, as it does when `mvn` starts Maven
  through it; configure `<executable>` or a toolchain to have the check read the javac that runs.
- **A JDK 21 or later for the generator.** Its `java` must exist, have a `javac` beside it, and belong to a JDK
  whose `release` file, when it has one, declares 21 or later. On Windows, a `javaExecutable` whose name has no
  extension and names no file stands for the `.exe` beside it.
- **The processor's own generated-sources directory left where GraphMaquina owns it.** A configured
  `<generatedSourcesDirectory>` / `<generatedTestSourcesDirectory>` must match the request's, and so must the
  directory of a last `-s` compiler argument, resolved where javac runs.
- **One encoding for a javac inside Maven.** A javac that compiles inside Maven through the `javax.tools` API reads
  every source, those annotation processors generate included, in the configured encoding, `<encoding>` or
  `project.build.sourceEncoding`, but writes the sources an annotation processor generates in the last
  `-encoding` it is given. A last `-encoding` compiler argument that names another charset than the configured
  encoding is therefore refused, when this JVM supports both: javac would read those sources back in another
  charset than it wrote them in, which garbles the characters the two encode differently, or fails javac after
  the preparation, as it does for the StateGraph processor's ASCII sources written in UTF-16 and read back in
  UTF-8. An alias of the configured charset passes, and so does any `-encoding` for a javac the compiler forks or
  runs through `com.sun.tools.javac.Main`, which reads and writes in the last one.

Paths are compared once their symbolic links are resolved, as javac resolves the directories it writes into, so
naming the project through a link, as `mvn -f <link>/pom.xml` does, or a link inside a configured path, makes no
two names of one directory differ.

The checks read the compiler arguments as `maven-compiler-plugin` 3.15.0 passes them to javac: the entries of
the deprecated `<compilerArguments>` map, sorted by key, each key once with its last value, as Maven converts
that map; then `<compilerArgument>`; then the values of `<compilerArgs>`. The test compilation takes
`<testCompilerArguments>` and `<testCompilerArgument>` over the first two when they are set. Each argument is
evaluated as Maven evaluates a `String` parameter it hands the compiler, as far as that can be told when the checks
run: `${settings.localRepository}` or `${session.executionRootDirectory}` is completed, and `$$` becomes `$` in a
value that holds no `${…}`, or after the last `${…}` of one that does. An argument that is one expression naming an
object Maven does not take for a `String`, such as `${project.file}`, or that evaluates to an empty text, is left
out, as javac never gets it, and a `<compilerArguments>` value that does so leaves its key alone. An expression that
cannot be completed yet stays as it is written: one about the running goal or its plugin, or a property not set when
the checks run, which a plugin bound to a later phase may still set. A list parameter written as comma-separated
text, such as `<compilerArgs>-Xlint:all,-Werror</compilerArgs>`, is evaluated, then split on every comma, as Maven
splits it, each part evaluated again, and so are `<annotationProcessors>`, `<compileSourceRoots>` and every list of
patterns, for these checks and, for the roots and the patterns that select sources, for the sources passed to the
generator. `<annotationProcessors>` can therefore name its processors as text,
`<annotationProcessors>p.First,p.Second</annotationProcessors>`, or in one element that holds a comma list,
`<annotationProcessor>p.First,p.Second</annotationProcessor>`, as well as one name per element: javac gets the
names joined by commas and splits them on every comma again.

The processor-list check refuses only a list that leaves out the StateGraph processor or holds a name that is
not a valid qualified class name. Other mistakes in a processor list are javac's to report, such as a
well-formed name of a class that is on no processor path, a misspelt one for instance, a name of a class that is
no annotation processor, or a `-processor` argument with nothing after it: the compilation then fails after the
preparation, like any compilation javac rejects, and the next preparation cleans up after it from the pending
inventory it left.

## Troubleshooting

| Symptom | Cause and remedy |
| --- | --- |
| `SG599` "could not write …" | the processor could not create or write an output. Follow the named type/path and underlying error using [processor output failures](#processor-output-failures-sg599), then rerun the full lifecycle after correcting the cause. |
| `SG602` "…names no annotation manifest…" | javac ran without `-Agraphmaquina.annotationManifest=…`. In Maven, add the argument to that execution; in Gradle, the source set is not participating — declare `graphmaquina-processor` in its annotation-processor configuration. |
| `SG602` "the annotation manifest … does not exist", "…there is no manifest at …" / stale manifest | the generation step did not run before javac or before the preparation. Never invoke `compiler:compile` on its own; run a lifecycle phase so `graphmaquina:generate` and `graphmaquina:prepare` precede it. |
| `SG602` "…which this compilation never processed…" | javac compiled a machine implicitly, from the source path, and javac does not process such a source. Pass every source of the compilation to javac. |
| `SG602` "…is still compiled from a previous compilation that nothing retired…" | javac ran again over a class output that still holds the previous companions. Run the lifecycle, whose preparation retires them, or clean the build. |
| `SG602` "…no class file in … belongs to the carrier…" | javac's debug setting left the `SourceFile` attribute out of the class files, which the completion reads: `-g:none`, or a `-g:` list without `source` such as `-g:lines,vars`. Compile with javac's default debug setting, with `-g`, or with a `-g:` list that includes `source`. Both plugins refuse such a setting before anything is retired when they can read it. |
| `SG602` "…which carries no export index META-INF/graphmaquina/exports.json…" | a dependency on the classpath, a jar or a directory of classes, carries a machine a set holds or a generated symbol a declaration binds, but no export index, which is what lists them: an assembly step dropped `META-INF/graphmaquina`, or the dependency was not built through the complete lifecycle. The message names that jar or directory. Package the index with the classes it describes, or rebuild the dependency through the complete lifecycle. |
| `SG602` "…whose export index META-INF/graphmaquina/exports.json does not list it, so that index is stale or another build's…" | a dependency on the classpath, a jar or a directory of classes, carries a machine a set holds or a generated symbol a declaration binds, but its export index does not list it: the index is stale, as one an older build left is, or another dependency's, as an assembly step that overwrites `META-INF/graphmaquina` leaves it. The message names that jar or directory. Package the index the dependency's StateGraph build publishes with the classes it describes, or rebuild the dependency through the complete lifecycle. |
| `SG602` "inventory path '…' …" | a manifest of the work directory lists an owned source that is not a relative path to a Java source: one that no path can spell, such as one holding a NUL character, one that is absolute or has a root, one with a `..` segment, one that names the directory itself, such as `.`, or one that is no `.java` file, such as `x`. Clean the build. |
| `SG602` "member 'encoding' names no charset this JVM supports in …" | the source encoding of the compilation is no charset of the JVM the generator runs under: in Maven the encoding javac reads in, from `<encoding>` or its property `encoding`, `project.build.sourceEncoding` or an `-encoding` compiler argument; in Gradle the generation task's `encoding`, by convention the encoding javac reads the sources in, from `options.encoding` or an `-encoding` compiler argument. Name a charset that JVM supports, such as `UTF-8`. |
| `SG602` "number with an exponent out of range in …" | the request, a manifest, an inventory or a dependency's export index holds a number whose exponent is out of range, such as `1e99999999999`, so it was edited or damaged after the tools wrote it. Clean the build, or rebuild the dependency that published the index. |
| `SG602` "malformed JSON in …: an unpaired surrogate …" | the request, a manifest, an inventory or a dependency's export index escapes, in a string or a name, a surrogate that is not half of a pair, such as `\ud800`, which no UTF-8 file can hold, so the tools did not write it. Clean the build, or rebuild the dependency that published the index. |
| `SG601` at a machine | the generator could not resolve a type it needs (the entity, the state enum, a host), or could not parse an original source at the compilation's release. The message names the type and the declaration. |
| `SG603` | a generated name collides or cannot be written: names that collide after PascalCase normalization, such as `id` / `_id`; a generated type that an original source already declares, that would shadow a class of the classpath or overwrite a file of the generated directory the previous generation did not own, or that differs from an original only in case where the volume does not tell names apart; two generated types, or two symbol types of one references container, whose names differ only in case where a volume that holds their files does not tell names apart, such as the containers `OuterInnerRefs` and `OuterinnerRefs` of the hosts `Outer.Inner` and `Outerinner`, or the companions `LibraryRuntime` and `LIBRARYRuntime` of the sets `Library` and `LIBRARY`; or a type that takes over a name the generated code writes, such as a type of the package named `io`, `java` or like a `java.lang` type. The diagnostic names both sides; rename one of them and its references, then regenerate. |
| `SG603` "…would overwrite the unowned file …: nothing in the work directory … records a file a previous generation wrote…" | a file stands where a generated source goes, and nothing in the work directory records that a previous generation wrote it, as after that directory, `build/graphmaquina/<sourceSet>` in Gradle or `target/graphmaquina/main` and `target/graphmaquina/test` in Maven, was deleted while the generated sources stayed. The generator never overwrites a file it cannot tell from one somebody else put there. Run `clean`, or delete the generated sources, then build again; move a hand-written file out of the generated source directory first. |
| `SG603` "…share one canonical name…", "the class file of … of the unnamed package names both …" | a class of the unnamed package is named like a package, as a class `shop` beside the package `shop`, so one canonical name names two types: two machines, one of the package and one nested in the class, which a manifest names by that name alone, or two types in a class file of the unnamed package that the generator has to tell apart, such as two of its supertypes, which it cannot do by their canonical name. Rename one of them, or the class. |
| `SG603` "…which a symbolic link takes out of …", "…would be written through a symbolic link that leads out of …" | a symbolic link inside an output directory, the class output or a generated source directory, leads out of it, and the step would remove or write a file outside it through that link. Nothing outside the directory is touched. Remove the link, then run the lifecycle again. |
| `SG603` "…claims '…', which is not a file under …", "…which is not a class file under …", "…which is not a source file under …" | an inventory of the work directory names an entry that is not a file under its output root: a blank one, one that no path can spell, such as one holding a NUL character, or one that is absolute, has a root, names the root itself or leads out of it; or a binary that is no `.class` file, or a source that is no `.java` file, such as `x`. Clean the build. |
| `package OrdersGraph does not exist` | the generated vocabulary is missing: generation did not run, or the source root was not registered. In Gradle, check that `generateStateGraphApi` ran; in Maven, that `sg-generate` ran in `generate-sources`. |
| "the StateGraph generator needs a Java 21 toolchain or later" (Gradle), "…needs Java 21 or later, but … belongs to Java …" (Maven) | in Gradle, raise the JDK javac compiles with, from the project's toolchain or the compile task's own, or the generation task's `javaLauncher` when the build sets one, to 21+; in Maven, point `graphmaquina.javaExecutable` / `jdk` toolchain at a JDK 21+. |
| "…needs the jdk.compiler module…" | the installation the Gradle generation task's `javaLauncher` names, or Maven's `graphmaquina.javaExecutable` or `jdk` toolchain, has no `javac` beside its `java`: it is a runtime, or, in Maven, a launcher outside a JDK's `bin` directory. Point it at a full JDK 21 or later, in Maven at the `java` in that JDK's `bin`. |
| "…cannot parse the sources of … at release … under its javaLauncher…" (Gradle) | the `javaLauncher` the build gives the generation task is a JDK older than the release javac compiles at. Give it a JDK of that release or later, or leave it unset, so the generator runs on the JDK javac compiles with. |
| "…The two must be the same JDK…" | the generator's `java` and javac belong to different JDK homes. Point `graphmaquina.javaExecutable` at the `java` in the `bin` directory of the JDK javac compiles with, not at a wrapper launcher, or have javac compile with the generator's JDK. |
| "…is not on the `<annotationProcessorPaths>` …", "…is not on the dependencies of this project, and the … declares no path in `<annotationProcessorPaths>`…" (Maven) | the processor is missing from the compiler's processor path, or, when the execution declares none, from the project's dependencies. Declare it in `<annotationProcessorPaths>`, after Lombok. |
| "…gives javac the processor path it takes, the only place it then looks for annotation processors, and that path reaches no …StateGraphProcessor.class…" (Maven) | a `-processorpath` or `--processor-path` compiler argument does not reach the processor. When the path the message names is another compiler argument, the value after the option is missing or evaluated to nothing, as `${project.file}` does, so javac takes the next argument as the path. Add the processor's jar and the jars it depends on to that path, or remove the argument and declare the processor in `<annotationProcessorPaths>`. |
| "…makes javac look for annotation processors on a module path…" (Maven) | remove the `--processor-module-path` argument, and declare the processor in `<annotationProcessorPaths>`. |
| "…is only a dependency of this project…: from Java 23 on, javac runs no annotation processor it finds on the class path unless annotation processing is requested…" (Maven) | configure `<proc>full</proc>` for that execution, or declare the processor in `<annotationProcessorPaths>`, after Lombok. |
| "…switches annotation processing off…" | remove `proc=none` / `-proc:none`. |
| "…which leaves out io.github.graphmaquina.processor.StateGraphProcessor…" | the `-processor` list javac gets, from a compiler argument or, in Maven, from `<annotationProcessors>`, leaves out the StateGraph processor, and javac runs only the processors such a list names. Add `io.github.graphmaquina.processor.StateGraphProcessor` to it, after Lombok's processors when the project uses Lombok, or remove the list so javac runs every processor on its processor path. |
| "…whose name '…' is not a valid qualified class name…" | that list holds a name that is not a valid qualified class name, which the compiler cannot load: an empty or blank one, one with spaces around it, or, in Maven, a value of `<annotationProcessors>` left empty. Correct or remove that name. |
| "…run the annotation processors and compile nothing…", "…lets the build continue past a compilation…" | remove `proc=only` / `-proc:only`, or leave `failOnError` at its default, `true`. |
| "…compile only the sources whose class file is missing or older than the source…" | `useIncrementalCompilation` is set to false. Leave it at its default, `true`. |
| "…select no …/GraphCompilation….java…", "…leaves out …/GraphCompilation….java…" | the compiler's includes or excludes leave out the compilation carrier. Add `<include>io/github/graphmaquina/generated/**</include>`, or change the exclude so it matches nothing under `io/github/graphmaquina/generated`. |
| "…hold only empty values, which Maven turns into null…" | the compiler's includes hold only empty values, so it would select no source. Remove those empty values, so that `maven-compiler-plugin` includes every source. |
| "…reads the annotation manifest X…, while the StateGraph generator writes Y" | copy the argument the message prints into that execution. A relative path is resolved where javac runs, so, when javac compiles inside Maven, in a module of a build started from its parent it names another file; the printed argument is absolute. |
| "…javac write the class files of … without the SourceFile attribute…" (Maven) | a `<debuglevel>` or a `-g:` compiler argument leaves `source` out. Remove that setting, or have one `-g:` option list `source`, as `<debuglevel>lines,vars,source</debuglevel>` does. |
| "…write its classes …, while StateGraph completes this compilation in …" (Maven) | an `<outputDirectory>`, `maven.compiler.outputDirectory`, `<multiReleaseOutput>true` or a `-d` compiler argument moves the classes. Remove it, or move the project's class output with `<outputDirectory>` or `<testOutputDirectory>` in `<build>`. |
| "…name two charsets for a javac that compiles inside Maven through the javax.tools API…" (Maven) | the configured encoding and the last `-encoding` compiler argument name different charsets, and javac would read back the sources annotation processors write in the other one. Remove that `-encoding`, or configure `<encoding>` to the charset it names. |
| "…execution of maven-compiler-plugin, bound to …, runs after the … execution of graphmaquina-maven-plugin, …, which completes this compilation…", "…runs before the … execution of graphmaquina-maven-plugin, …, which prepares this compilation…" (Maven) | declare `maven-compiler-plugin` before `graphmaquina-maven-plugin` in `<plugins>`, and bind the compiling execution to the phase of `complete`, as the message says. |
| "…runs the executions … of its compile goal for this project, and each of them compiles …" (Maven) | two executions of the compiler would compile the generated vocabulary. Keep one of them, bind the others to the phase `none`, or give them `<compileSourceRoots>` of their own that leave out the generated directory. |
| "…makes the annotation processor write its sources to …" (Maven) | a `-s` compiler argument names another directory than the processor's generated-sources directory. Remove that argument. |
| "…the `<compileSourceRoots>` of … replace the source roots of the project and leave out …" (Maven) | add the `<compileSourceRoot>` the message prints, or leave `compileSourceRoots` unset. |
| "…the `<incrementalExcludes>` pattern … leaves … out of the check the compiler makes for stale sources…" (Maven) | change that pattern so it matches nothing under `io/github/graphmaquina/generated`. |
| `[INFO] StateGraph skips <id>: …` | the project is packaged as `pom`, or `maven.main.skip` / `<skipMain>` / `maven.test.skip` / `<skip>` switches that compilation off. Nothing was generated, retired or published; this is not an error. |
| "…cannot run with options.failOnError = false…", "…cannot run with -proc:only…", "…cannot run with -proc:none…" (Gradle) | remove that setting from the compile task. |
| "…cannot run with an options.annotationProcessorPath that is empty or null…" (Gradle) | a setting of the compile task empties its annotation processor path. Remove it, so the path holds the source set's annotation processors again. |
| "…javac then leaves the SourceFile attribute out of every class file…" (Gradle) | a debug setting of the compile task drops the attribute the completion reads. Remove it, or add a `-g:` list that names `source`, such as `-g:source,lines,vars`, to the compiler arguments. |
| "…cannot run with options.encoding = "…" and the compiler argument -encoding …" (Gradle) | the two name different charsets, and Gradle's own compiler would read back the sources the StateGraph processor writes in the other one. Remove that `-encoding`, or set `options.encoding` to the charset it names. |
| "…cannot run with the compiler arguments its argument providers give…" (Gradle) | an argument provider changes the release or the encoding javac takes, which the generation never reads. Give `--release` or `-encoding` in `options.compilerArgs`, or set `options.release` or `options.encoding`, instead. |
| "the StateGraph generation … reads …, which task ':compileJava' does not compile…" (Gradle) | the compile task has a filter given as a spec or a closure, or a source put in place of the source set's. Give the filter to the source set's java sources, or to the task as an include or exclude pattern; for a replaced source, paste the line the message prints for your DSL into the source set's block and remove the source from the task. |
| "task ':compileJava' compiles …, which the StateGraph generation … does not read…" (Gradle) | a source was given to the compile task beside the source set's. Give the directory the message names to the source set with `java.srcDir(...)`, and remove it from the task. |
| "task ':compileJava' does not compile …" (Gradle) | the compilation leaves out a generated file. Add directories with `java.srcDir(...)` instead of replacing them, or add the generated directory back with the line the message prints, or remove a source or pattern set on the compile task. |
| "…there is no compilation carrier of … under …, although every generation emits one there…" (Maven) | the carrier source the generation emitted was missing when `prepare` or `prepare-test` ran, as when it was deleted after `generate`. Run the lifecycle again, so that `generate` or `generate-test` emits it before the preparation. |
| `complete` (Maven) or `complete-compile` (Gradle, `SG602`) fails naming `pending-exports.json` | javac ran but the annotation processor published no index: it was not on the processor path of that compilation, or did not run. |
| `[INFO] StateGraph completes nothing for <id>` | javac did not run at all in this invocation (for example `mvn generate-sources`). Nothing was retired; this is a no-op, not an error. |

### Processor output failures (SG599)

SG599 reports a technical failure while producing compiler outputs. The message identifies which of the
three paths failed and includes the underlying error:

| Message | Output and corrective action |
| --- | --- |
| `could not write <qualified type>` | A generated Java source could not be created or written through javac's `Filer`. Check the processor's generated-sources directory, write permissions and available space. If the error reports an attempt to recreate a file, resolve the competing generated type or processor output. |
| `could not write META-INF/graphmaquina/pending-exports.json` | Encoding the provisional export index, staging its temporary file, or writing the resource under javac's class output failed. Inspect the underlying error and check both the class-output directory and the JVM temporary directory (`java.io.tmpdir`), including permissions and available space. |
| `could not write the processor inventory <path>` | The manifest's `processorInventory` path is invalid, its parent could not be created, or the inventory could not be written. Correct the work-directory configuration or the filesystem condition named in the message; verify that a regular file is not occupying a required directory. |

After correcting the reported cause, rerun the normal Gradle or Maven generation/compilation lifecycle so
preparation, javac and completion run together. SG599 is an error: a compilation that reports it has not
successfully completed publication of its outputs.

### Repeated builds, renames and deletions

Both adapters retire what the previous compilation owned before the next javac, and publish an index only for
what javac actually accepted, so:

- building twice without changes regenerates nothing and republishes nothing (Gradle reports
  `UP-TO-DATE`; Maven rewrites identical artifacts);
- renaming or deleting a machine, an action, a property or a state removes the generated symbols and facades
  of the old names from the next jar, **without `clean`**;
- deleting the last graph declaration together with every marker that exports a symbol, such as `@GraphAction`
  or `@GraphProperty`, leaves an empty index and a jar with no generated class in it; while such markers remain,
  the index lists their symbols and the jar keeps the references containers generated for them;
- a javac failure leaves the pending inventory behind, seals nothing and publishes no index, so a broken
  compilation can never ship the index of the previous one.

## Limits

- **A pair must be generated by the same compilation.** `@GraphPeers` may only name machines this very
  compilation generates an API for, because the vocabulary of a pair, which declares the owner's watches and
  sub-machines over the peer, is typed with both machines' states; a peer that lives in another module or in a
  dependency jar is refused with SG608, and cross-jar watches and sub-machines are deferred.
- **The generator parses without `--enable-preview`.** No member of the request carries it, whatever javac is
  given. An original source using a preview feature that javac's parser checks, such as string templates on
  JDK 21, is refused with SG601; one using a preview feature javac checks only after parsing, such as primitive
  patterns at release 25, is read like any other source.
- **The generator reads Java sources only.** It parses the `.java` sources of the compilation, so a marker on a
  Kotlin declaration is never read. In a Gradle source set that mixes Kotlin and Java, a Java machine whose entity
  or state enum a Kotlin source declares is refused with SG601, that type unresolved. Declare the machines, the
  sets, the types a machine is over and every class that carries a marker, such as a binding host, in the Java
  sources of the compilation, not in its Kotlin sources; a marked class can also come from a dependency built
  through the GraphMaquina lifecycle.
- **`graphmaquina-core` is an automatic module.** Its jar carries no `module-info` and names the module
  `io.github.graphmaquina.core` in its manifest, and javac can warn about a module that requires an automatic one.
  `requires transitive io.github.graphmaquina.core` gets the `requires-transitive-automatic` warning, which javac
  reports by default, so `-Werror` alone fails on it; turn it off with `-Xlint:-requires-transitive-automatic`, and
  under `-Xlint:all` turn off `requires-automatic` too, which javac reports in its place. A plain `requires` gets
  no warning by default; under `-Xlint:all` it gets the `requires-automatic` one, and the `exports` one for every
  GraphMaquina type the API of an exported package names, the generated companions' included, which
  `-requires-automatic` and `-exports` in the `-Xlint:` list turn off.
