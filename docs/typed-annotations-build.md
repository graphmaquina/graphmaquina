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

Both build adapters implement exactly those four steps. The generator and the processor need each other: the
generator without the processor would compile a vocabulary nobody indexes, and the processor without the generator
would find no manifest and refuse the compilation with SG602.

The adapters run the three generator steps as a separate Java process,
`io.github.graphmaquina.codegen.CodegenMain <generate|prepare-compile|complete-compile> --request <file>`, with the
request file the adapter writes. It exits with `0` on success, with `1` when the operation fails, writing a message on
its error stream that carries the `SG` code and the source location when the failure has them, and with `2` for
arguments it does not accept. A build that ignored either failure status would compile against the vocabulary of a
previous run.

| Artifact | Coordinates | Role |
| --- | --- | --- |
| Runtime and annotations | `io.github.graphmaquina:graphmaquina-core` | the engine, the markers, the symbol categories |
| Generator | `io.github.graphmaquina:graphmaquina-codegen` | pre-javac generation, run as a separate process by the adapters |
| Annotation processor | `io.github.graphmaquina:graphmaquina-processor` | runs inside javac, emits the facades and publishes the index |
| Gradle adapter | plugin id `io.github.graphmaquina` | the Gradle build integration |
| Maven adapter | `io.github.graphmaquina:graphmaquina-maven-plugin` | the Maven build integration |

Keep the runtime, generator, processor and adapter at the same version, set once as the `graphmaquinaVersion`
Gradle property or the `graphmaquina.version` Maven property. The processor emits `<Machine>Runtime` and
`<Set>Runtime`. Generated types normalize underscores and nesting dots to PascalCase, preserving internal case and
acronyms.
See the [generated naming reference](graph-model.md#generated-names).

Only `graphmaquina-core` is a dependency of the application. The generator and the processor are build-time only
and never reach the runtime classpath.

For the first machine, follow the [annotation generation walkthrough](generation.md#how-your-flow-annotations-are-generated).
It shows how to generate vocabulary from a minimal interface before writing the flow, or build complete
sources that already reference generated annotations. The generator reads source syntax before javac
type-checks the application; no previously compiled machine is required.

## Contents

- [Where the artifacts come from](#where-the-artifacts-come-from)
- [Gradle](#gradle)

## Where the artifacts come from

GraphMaquina is published to Maven Central under `io.github.graphmaquina`, which is all a consumer of a release
needs; until Central lists the version these pages document, 2.0.1, a consumer installs it locally as below.
The rest of this section covers the other two cases: testing a build that is not released yet, and serving
every component, including the Gradle implementation and its marker, from an internal repository. The source
repository is private and requires authorized access; the published artifacts are public.
See the [guide](installation.md#configure-gradle) for a complete consumer configuration.

To test local changes, publish from an authorized checkout of this repository, under a version string of your
choice, the one the consumer then sets as `graphmaquinaVersion` or `graphmaquina.version`:

```bash
./gradlew -PgraphmaquinaVersion=x.y.z \
          :graphmaquina-core:publishToMavenLocal :graphmaquina-codegen:publishToMavenLocal \
          :graphmaquina-processor:publishToMavenLocal :graphmaquina-maven-plugin:publishToMavenLocal \
          :graphmaquina-gradle-plugin:publishToMavenLocal
```

The command compiles the root build's modules, so it first runs the
[documentation check](repository-build.md#build-verify-and-release-this-repository), which needs lychee 0.24 on the
`PATH`. The Gradle adapter is an included build, which the root build reaches as `:graphmaquina-gradle-plugin`, and
`-PgraphmaquinaVersion` applies to it too. Its task installs both
`io.github.graphmaquina:graphmaquina-gradle-plugin` and the marker `io.github.graphmaquina:io.github.graphmaquina.gradle.plugin`, which is
what `plugins { id("io.github.graphmaquina") version … }` resolves; `./gradlew -PgraphmaquinaVersion=x.y.z -p
graphmaquina-gradle-plugin publishToMavenLocal` installs the same two alone, without the documentation check. Add
`:graphmaquina-test:publishToMavenLocal` to the command when the consumer's tests use the testing module. To publish to a file or internal repository
instead, declare that repository in the publishing configuration of each module, `graphmaquina-gradle-plugin` included,
then run `publishAllPublicationsTo<Name>Repository` and `:graphmaquina-gradle-plugin:publishAllPublicationsTo<Name>Repository`:
the Gradle adapter is an included build, which a bare task name does not reach, and the build itself declares only its
`adapterTests` and `centralStaging` repositories.

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

A source set takes part **when, and only when, its annotation-processor configuration, or one it extends, declares
`io.github.graphmaquina:graphmaquina-processor`** — `annotationProcessor` for `main`, `testAnnotationProcessor` for
`test`, and so on — as a module dependency, or as a project dependency on a project named `graphmaquina-processor`.
A file dependency on the processor's jar does not count. The processor is what publishes the index of what javac accepted, so a source set compiled
without it could never complete a compilation the plugin prepared.

A source set that does not declare it is left as the `java` plugin configured it: no generated source root, no
annotation manifest, no lifecycle around javac, and no `-Xlint:processing` warning about a processor that
claims none of its annotations. The exceptions are `options.isIncremental`, which the plugin switches off on
the compile task of every source set — see below — and the exclusion of the generated compilation carrier from
every source set's sources jar and javadoc. Its generation task still exists, and skips itself with the
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
| `build/graphmaquina/<sourceSet>/owned-outputs.pending.json` | the pending inventory: what the preparation is retiring; left behind by a compilation javac rejects, and consumed by the next preparation |
| `build/graphmaquina/<sourceSet>/processor-inventory.json` | what the processor emitted, written during javac and read by the completion step |
| `build/classes/java/<sourceSet>/META-INF/graphmaquina/exports.json` | the published index, packaged into the jar |
| `build/classes/java/<sourceSet>/META-INF/graphmaquina/pending-exports.json` | the provisional index the processor writes during javac, which the completion promotes to `exports.json` |

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

The generation task reads the original sources it parses from `sources`, and tracks where each lies in the project
through `sourcePaths`, so moving a source between directories of the source set reruns it. Its `participates`,
`projectDirectory`, `compilationOutputLocations`, which holds the class output and the processor's source output as one
input, `sourcesOutput`, `workDirectory`, `requestFile`,
`javaLauncher`, `generatorClasspath`, `release`, `encoding` and `compilationId`, which defaults to
`group:name:sourceSet`, or `name:sourceSet` when the project's group is empty, are conventions: a value a build sets wins whenever it sets it, and
the compilation follows what the task holds, from the source root, the manifest and the sealed inventory to the
launcher and the classpath `prepare-compile` and `complete-compile` run with. Paths a build adds to
`generatorClasspath` join the default only when they are added after the plugin configured the task; added
before, they replace it. `classesOutput` and `processorSourcesOutput` follow the compile task, and
`annotationManifest` follows the work directory: a build that sets any of the three is refused. `applicationClasspath`, against
which the generator resolves headers and the published indexes of dependencies, is the source set's compile classpath
without its own class output: paths a build adds with `from()` join it, and only `setFrom` replaces it.

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
graphmaquina { generatorVersion = "2.0.1" } // explicit override; keep all components aligned
dependencies { add("graphmaquinaGenerator", project(":graphmaquina-codegen")) }  // inside this repository
```

Declaring any dependency on that configuration replaces the default, which is how `graphmaquina-example` runs
against the sibling project instead of a published artifact. Each generation task takes that configuration as
the convention of its `generatorClasspath`; a classpath set on the task wins, and the `prepare-compile` and
`complete-compile` of its source set run over it too. What a generator run that completes writes on its error
stream is logged as a warning, as the Maven plugin logs it.

---

Next: [The Maven adapter](build-maven.md). Back to the [documentation index](README.md).
