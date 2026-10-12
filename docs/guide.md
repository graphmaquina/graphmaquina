# GraphMaquina

[![Maven Central](https://img.shields.io/maven-central/v/io.github.graphmaquina/graphmaquina-core)](https://central.sonatype.com/artifact/io.github.graphmaquina/graphmaquina-core)

![GraphMaquina: typed graphs and predictable transitions, illustrated by a ticket that goes from OPEN through REVIEW to DONE, or from OPEN to FAILED through the failure route of SUBMIT.](images/graphmaquina-overview.svg)

Typed, graph-based state machines for Java 21. Declare the transitions your domain allows as identified
edges, give every state a reason for refusing the rest, and let the build validate the whole graph before the
application runs.

GraphMaquina combines a pre-compilation code generator, a javac annotation processor and an in-process runtime.
Your application calls a generated, typed facade. Actions, guards, hooks, properties and watched states are
bound through Java symbols rather than string names.

This guide documents the API this repository builds. The examples never name a version: they read one from a
`graphmaquinaVersion` Gradle property or a `graphmaquina.version` Maven property, set once per build. Install
that version from a checkout into your local Maven repository first — [configure Gradle](#configure-gradle)
opens with the one command that does it, and [configure Maven](#configure-maven) uses the same one — then set
the property to the version you installed. `mavenCentral()` stays in both builds for third-party
dependencies, JUnit among them, not for GraphMaquina itself.

This README is the developer guide for the current implementation. The quickstart has complete, runnable
files. Most later examples are extensions or fragments of the quickstart or of the repository's example module, whose
shop and billing domains the guide quotes.

## Contents

- [What GraphMaquina provides](#what-graphmaquina-provides)
- [Visual guide](#visual-guide)
- [Requirements and artifacts](#requirements-and-artifacts)
- [How your flow annotations are generated](#how-your-flow-annotations-are-generated)
- [Publication and verification status](#publication-and-verification-status)
- [Configure Gradle](#configure-gradle)
- [Configure Maven](#configure-maven)
- [Quickstart: a complete ticket machine](#quickstart-a-complete-ticket-machine)
- [Understand the graph](#understand-the-graph)
- [Annotation and binding reference](#annotation-and-binding-reference)
- [Actions, guards and failure routes](#actions-guards-and-failure-routes)
- [Handle transition outcomes](#handle-transition-outcomes)
- [Asynchronous work and timeouts](#asynchronous-work-and-timeouts)
- [Build and manage machine sets](#build-and-manage-machine-sets)
- [Read peers and cascade transitions](#read-peers-and-cascade-transitions)
- [Watch other machines](#watch-other-machines)
- [Compose machines](#compose-machines)
- [Commit, hooks and observers](#commit-hooks-and-observers)
- [Debug a call: logs and the call's flow](#debug-a-call-logs-and-the-calls-flow)
- [Concurrency, identity and persistence](#concurrency-identity-and-persistence)
- [Inspect graphs and export diagrams](#inspect-graphs-and-export-diagrams)
- [Test your machines](#test-your-machines)
- [Generation lifecycle and IDE setup](#generation-lifecycle-and-ide-setup)
- [Multiple modules and published JARs](#multiple-modules-and-published-jars)
- [Programmatic DSL](#programmatic-dsl)
- [Diagnostics and troubleshooting](#diagnostics-and-troubleshooting)
- [Known limitations](#known-limitations)
- [Build, verify and release this repository](#build-verify-and-release-this-repository)
- [Migrating from 1.x](migration-2.0.md)
- [Component benchmarks](../benchmarks/README.md) — graph-only harness, the V/E cost evidence and the measured cost of
  the call's flow and logs; the 1.x figures are historical
- [Further reference](#further-reference)
- [License](#license)

## What GraphMaquina provides

| Capability | How it works |
| --- | --- |
| A graph of identified transitions | You declare edges `(from, transition, to)`. An identifier selects the edge, so parallel edges and self-loops are ordinary declarations. |
| Compile-time structural validation | Checks include reachability, terminal states, intentional cycles, duplicate `(from, id)` keys, failure routes, watch rules and sub-machine bindings. |
| Typed operations | A machine interface exposes domain operations such as `submit(ticket)` or `pay(order)`, returning `Outcome<E, S, T>`. |
| Explicit bindings | Generated `Refs` classes identify marked methods and properties; the processor checks their roles and signatures. |
| Synchronous and asynchronous actions | Actions and guards may return values or `CompletionStage` results. The runtime waits under configured deadlines. |
| Coordinated state changes | Cascades share a unit of work; entity state setters run after the root transition succeeds or selects a handled failure route. |
| Declared watches | After a peer commits, a matching owner can be reevaluated and execute the identifier the first matching rule names. |
| Sub-machines | A state can hold another machine. Entering the state starts the child at its initial state, a call to a child whose parent does not hold it there is rejected, and the step that ends the child runs the parent's exit in the same unit of work. See [compose machines](#compose-machines). |
| Reasoned refusals | Every node carries a policy, so a request with no edge gets an explanation instead of a fabricated transition. |
| Inspectable execution | Outcomes carry transition records, watch reactions, the sub-machines a call started and the call's flow; technical aborts carry diagnostic details where applicable. |
| Call logs | Each call can log its start, the hops and sub-machine starts it committed, and how it ended, at `DEBUG`, and each step it took, at `TRACE`, through `System.Logger`; off by default. See [debug a call](#debug-a-call-logs-and-the-calls-flow). |
| The call's flow | Actions put typed values in a flow that every step of the same call reads, guards, cascaded steps and the exits of sub-machines included, along with the entity the call was made for. A failure route drops the writes of the step that failed. Post-commit hooks that take a `CommitContext` read and write it too, so a value can reach the entity only once the call commits. With `recordTrail`, the flow also lists the hops and sub-machine starts the call took, each with the instant it took it, and, when the call is rejected or throws, how it ended. The caller receives it frozen, in the outcome or in the call's failure. See [the call's flow](#the-calls-flow). |
| Graph inspection | Read the declared edges from the definition or export a deterministic Graphviz state diagram, which draws each sub-machine inside the state it is bound to. |

GraphMaquina manages state transitions in objects you supply. It does not provide a database, entity locking,
a durable job queue, HTTP endpoints or distributed transactions. See
[persistence and concurrency](#concurrency-identity-and-persistence)
for the responsibilities that remain with your application.

The build and runtime have separate jobs:

![Build pipeline: declare Java entities and machines, generate vocabulary and a manifest, compile and validate facades, then publish accepted exports. At runtime the application uses the typed facade and core.](images/build-pipeline.svg)

## Visual guide

Follow the pictures for an overview, then use the linked sections for declarations, commands and complete
examples. Each illustration is an SVG: open it to inspect labels at full size.

| Question | Diagram | Read next |
| --- | --- | --- |
| What does GraphMaquina do? | [Typed graphs, predictable transitions](images/graphmaquina-overview.svg) | [What GraphMaquina provides](#what-graphmaquina-provides) |
| What runs at build time, and what at runtime? | [From declarations to a running machine](images/build-pipeline.svg) | [Generation lifecycle](#generation-lifecycle-and-ide-setup) |
| How can my source use annotations that do not exist yet? | [Source → vocabulary → compilation](images/annotation-bootstrap.svg) | [First generation](#how-your-flow-annotations-are-generated) |
| Which transitions are permitted? | [The ticket graph](images/ticket-workflow.svg) | [Understand the graph](#understand-the-graph) |
| Why did an operation apply, reject or fail? | [Edge selection and outcomes](images/operation-decisions.svg) | [Outcomes](#handle-transition-outcomes) |
| Which thread waits for my service? | [Direct call and async submission](images/async-execution.svg) | [Calling styles](#choose-the-calling-style) |
| How much time is left for an action? | [Root and action deadline timeline](images/async-deadlines.svg) | [Deadline rules](#deadline-rules) |
| What happens if the service finishes too late? | [Timeout and late completion](images/async-late-completion.svg) | [Cancellation](#timeout-cancellation-and-late-completion) |
| What can I safely use in a callback? | [Context and thread boundary](images/async-context-boundary.svg) | [Context ownership](#contexts-and-completion-callbacks) |
| Does a completed future mean the operation succeeded? | [Future result branches](images/async-future-results.svg) | [Result handling](#read-the-future-and-domain-result) |
| Do both machines commit together? | [Cascade versus watch](images/cascade-vs-watch.svg) | [Machine coordination](#read-peers-and-cascade-transitions) |
| When does an owner react to its peers? | [Watch reactions](images/watch-reactions.svg) | [Watch other machines](#watch-other-machines) |
| How does a state hold a whole machine? | [Sub-machine binding](images/submachine-binding.svg) | [Compose machines](#compose-machines) |
| When do hooks and reactions run? | [Commit lifecycle](images/commit-lifecycle.svg) | [Commit and hooks](#commit-hooks-and-observers) |
| What does a call share between its steps, and what does its caller get back? | [One call's flow](images/call-flow.svg) | [The call's flow](#the-calls-flow) |

## Requirements and artifacts

- JDK 21 or later, including `javac`, for generation and compilation. Use Java 21 as the baseline unless
  your application deliberately targets a newer release.
- The repository uses Gradle 9.2.1 through its wrapper. This is the verified Gradle version; the project
  does not declare a broader tested compatibility matrix.
- Maven consumers need Maven and a Java 21 JDK. The complete configuration below uses
  maven-compiler-plugin 3.15.0, matching the repository's integration fixtures.
- Lombok is optional for consumer entities and action classes. The quickstart uses handwritten accessors.
  If you use Lombok, place its annotation processor before GraphMaquina's.
- Keep the runtime, generator, processor and adapter on the same GraphMaquina version.

| Module | Coordinates / plugin ID | Add it to |
| --- | --- | --- |
| Runtime and public annotations | `io.github.graphmaquina:graphmaquina-core` | Application dependencies |
| Pre-compilation generator | `io.github.graphmaquina:graphmaquina-codegen` | Build tooling; resolved by the adapter |
| Annotation processor | `io.github.graphmaquina:graphmaquina-processor` | javac annotation-processor path |
| Typed testing assertions and probes | `io.github.graphmaquina:graphmaquina-test` | Test dependencies |
| Gradle plugin | plugin ID `io.github.graphmaquina` | Gradle `plugins` block |
| Maven plugin | `io.github.graphmaquina:graphmaquina-maven-plugin` | Maven build plugins |
| Reference application | `graphmaquina-example` | Source and tests in this repository; not an application dependency |

Every coordinate takes the version you installed, set once as the `graphmaquinaVersion` Gradle property or
the `graphmaquina.version` Maven property; the examples below read it from there.

The generator and processor are build-time tools. Do not add them as application runtime dependencies.
Application imports begin with `io.github.graphmaquina`.

## How your flow annotations are generated

You write the machine interface; GraphMaquina generates its annotation types before compiling your
application. The generator writes `TicketsGraph` from the name and type parameters of `Tickets`. You do not
need an already compiled `Tickets` class or an existing `TicketsGraph` to start.

There are three kinds of API:

| API | Who supplies it? | When is it available? |
| --- | --- | --- |
| `@GenerateGraphApi`, `@GraphProperty`, `@GraphAction`, `@GraphGuard`, `@StateGraphs`, `Machine` | The `graphmaquina-core` dependency | As soon as dependencies resolve |
| `TicketsGraph.Definition`, `TicketsGraph.Transition`, `TicketsGraph.StatePolicy`, `TicketRefs`, `TicketActionsRefs` | The pre-compilation generator, from your declarations | After the generation task/phase, before application compilation |
| `TicketsRuntime`, `TicketMachinesRuntime` | The annotation processor, from the validated flow | During application compilation |

![Original Java declarations enter a syntax-only generator, which emits typed vocabulary. javac then compiles original source and generated annotations together and produces Runtime implementations.](images/annotation-bootstrap.svg)

The generator creates the annotation types; you choose the flow by using those annotations. It does not
infer transitions from action method bodies, and it never invents a transition identifier from a name. For
example, `Machine<Ticket, TicketState, TicketTransition>` tells it that `initial`, `from` and `to` must use
`TicketState` and that `id` and `onFailure` must use `TicketTransition`. Your
`@TicketsGraph.Transition(id = ..., from = ..., to = ...)` declaration then specifies an actual edge.

### Start with a minimal machine

First configure the [Gradle plugin](#configure-gradle) or the [Maven lifecycle](#configure-maven), including
the annotation processor. Create `TicketState`, `TicketTransition`, `Ticket` and `TicketActions` from the
[quickstart](#quickstart-a-complete-ticket-machine). Then create
`src/main/java/com/example/tickets/Tickets.java` with this initial content:

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;

@GenerateGraphApi
public interface Tickets extends Machine<Ticket, TicketState, TicketTransition> {
}
```

This is enough to generate the annotation vocabulary. It is not yet a complete, valid flow: the definition,
policies and operations are added in the next step. The entity and both enums must be resolvable, and source
files must contain valid Java syntax.

From the application directory, run just generation with your chosen build tool:

```bash
# Gradle: generate vocabulary without compiling the application
./gradlew generateStateGraphApi

# Maven: execute the configured generate-sources phase without compiling the application
mvn generate-sources
```

With the quickstart's marked entity and action members, these files now exist in the same Java package as
their handwritten declarations:

| File | Gradle | Maven |
| --- | --- | --- |
| `TicketsGraph.java` | `build/generated/sources/graphmaquina/main/com/example/tickets/` | `target/generated-sources/graphmaquina/com/example/tickets/` |
| `TicketRefs.java` | Same directory | Same directory |
| `TicketActionsRefs.java` | Same directory | Same directory |

`TicketsGraph.java` contains the nested annotations `Definition`, `StatePolicy`, `Transition` and `Reject`,
with `StatePolicies`, `Transitions` and `Rejects` as their repeatable containers, and `Recovery`, which is
written only inside a `Transition`. `TicketRefs` contains
symbols for `@GraphProperty` members; `TicketActionsRefs` contains symbols for marked actions, guards and
hooks. Generation alone does not produce `TicketsRuntime` or `TicketMachinesRuntime`.

Refresh the IDE's build model if needed. You can now replace the minimal `Tickets.java` with the
[complete flow declaration](#ticketsjava), using `@TicketsGraph.Definition`, `@TicketsGraph.StatePolicy` and
`@TicketsGraph.Transition`. Add `TicketMachines.java` and `App.java` from the quickstart, then build and run:

```bash
# Gradle: generation, compilation and execution
./gradlew run

# Maven: generation and compilation; then use the quickstart's Java command to run App
mvn compile
```

During this build, the processor validates the flow and generates the `Runtime` implementations. javac also
compiles those generated implementations and application code that references them in the same invocation.

### You can also write the complete flow immediately

The initial generation-only step is optional: it is useful when you want the generated annotations available
in the editor before writing the flow. You may instead create all seven complete quickstart files at once,
including references to `TicketsGraph`, `TicketRefs` and `TicketMachinesRuntime`, and run the normal build.

This works on a clean checkout because reading Java syntax and checking Java types are separate operations:

1. The generator reads the original `.java` files as syntax. It uses the library markers, machine type
   parameters and marked members to create the vocabulary. References to the not-yet-generated annotations
   do not require those annotation classes to exist at this stage.
2. The build adapter adds the generated source directory to the compilation and supplies the generated
   manifest to the processor.
3. javac type-checks the handwritten sources together with the generated vocabulary. The processor
   validates your graph and emits its implementations. Compilation errors are reported at this stage if
   the flow or its bindings are invalid.

The build generates from source before attempting application compilation. A clean checkout does not need a
first compilation that fails, placeholder annotations, or a previously built JAR of your own machine. After
changing machine names, state or transition enums, or exported members, run the same build again to
regenerate the API.

An editor may underline missing generated types before the first generation. Use the commands above and
[refresh the generated roots](#ide-workflow); plain `javac` or an IDE build that bypasses the configured adapter
does not perform the required pre-generation step.

## Publication and verification status

GraphMaquina publishes to Maven Central under the verified namespace `io.github.graphmaquina`. A release
contains the runtime, generator, processor, testing module, Maven plugin, Gradle plugin and Gradle plugin
marker. The API documented here is the one this repository builds, so follow the guide against a build of
your own: [configure Gradle](#configure-gradle) opens with the command that installs it locally, and [typed
assertions, probes and graph snapshots](#typed-assertions-probes-and-graph-snapshots) describes the testing
module the same way.

| Check | Current development result |
| --- | --- |
| Automated library/build suites | `./gradlew test` runs the root build's ordinary suites; `./gradlew build` also runs `:graphmaquina-test:publishedArtifactTest` through `check`. The included Gradle adapter requires `:graphmaquina-gradle-plugin:test` explicitly. See [verification commands](#build-verify-and-release-this-repository). |
| README quickstart | [ReadmeQuickstartTest](../graphmaquina-test/src/test/java/consumer/ReadmeQuickstartTest.java) compiles the literal Gradle and Ticket sources with strict lint, runs `App` and checks `TicketsTest` against locally staged Maven artifacts. This JUnit consumer check covers local Gradle, not Maven execution or released artifacts. |
| Async guide | Migrated with the rest of this guide, then compiled and run through Gradle against locally staged artifacts: `AsyncApp` printed the output documented below it. It is not covered by an automated consumer check and was not re-run through Maven; the behaviour-check count reported for the 1.x async guide is historical. |
| Remaining acceptance work | Manual IntelliJ acceptance, a rerun of the async guide through both build tools, and full strict-lint acceptance of the external payments demo remain pending. |

Browse the [published artifacts](https://repo.maven.apache.org/maven2/io/github/graphmaquina/) or the
[core artifact on Maven Central](https://central.sonatype.com/artifact/io.github.graphmaquina/graphmaquina-core).
MVNRepository is an independent index; appearing in its search results is not required for Maven or Gradle
resolution. Sonatype does not control its update schedule; see the
[official explanation](https://central.sonatype.org/faq/mvnrepository/).

The signing identity is `Hugo G <60438622+0xhug0@users.noreply.github.com>`. The public fingerprint is:

```text
851F 7594 9D70 884A C95A AB56 455A D78F 0EC0 B9F1
```

This fingerprint and the public key allow consumers to verify signatures; they do not permit signing a
release. Private signing material is stored in 1Password. See [release signing](release-signing.md)
for verification commands and maintainer setup. Publishing a release does not resolve the
[known limitations](#known-limitations).

## Configure Gradle

The following files build and run the [ticket quickstart](#quickstart-a-complete-ticket-machine). Create a
separate application directory, `tickets-demo`, and put these files at its root.

First install GraphMaquina into your local Maven repository, from a checkout of this repository — a
different directory from `tickets-demo`. Choose any version string; it is the one every file below reads
from a single property:

```bash
./gradlew -PgraphmaquinaVersion=x.y.z \
    :graphmaquina-core:publishToMavenLocal \
    :graphmaquina-codegen:publishToMavenLocal \
    :graphmaquina-processor:publishToMavenLocal \
    :graphmaquina-test:publishToMavenLocal \
    :graphmaquina-gradle-plugin:publishToMavenLocal
```

That writes the runtime, the generator, the processor, the testing module, the Gradle plugin and the plugin
marker under `~/.m2/repository`. Gradle does not read that directory unless a build asks it to, which is why
`settings.gradle.kts` below declares `mavenLocal()`; Maven reads it as its own local repository and needs no
declaration at all.

### gradle.properties

```properties
graphmaquinaVersion=x.y.z
```

Use the version you installed above. The two scripts below read it from here, so no coordinate repeats
it.

### settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
    plugins {
        id("io.github.graphmaquina") version providers.gradleProperty("graphmaquinaVersion").get()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "tickets-demo"
```

A `plugins` block in a build script cannot interpolate a version, so the plugin request carries it here and
the build script applies the plugin by ID alone. `gradlePluginPortal()` is included for other plugins; this
guide does not require a separate GraphMaquina publication to the Gradle Plugin Portal.

### build.gradle.kts

```kotlin
plugins {
    java
    application
    id("io.github.graphmaquina")
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val graphmaquinaVersion: String = providers.gradleProperty("graphmaquinaVersion").get()

dependencies {
    implementation("io.github.graphmaquina:graphmaquina-core:$graphmaquinaVersion")
    annotationProcessor("io.github.graphmaquina:graphmaquina-processor:$graphmaquinaVersion")
}

application {
    mainClass = "com.example.tickets.App"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}
```

The GraphMaquina plugin reacts to the Java plugin; it does not apply Java itself. The `application` plugin is
used here only to provide `run` and a distribution containing runtime dependencies.

From the application directory, use an installed Gradle 9.2.1 to create a wrapper, or invoke the wrapper from
your GraphMaquina checkout with `-p` pointing to this directory:

```bash
gradle wrapper --gradle-version 9.2.1
./gradlew clean run
./gradlew build
./gradlew installDist
```

### Add Lombok when you need it

Add these dependency declarations before the GraphMaquina processor declaration:

```kotlin
compileOnly("org.projectlombok:lombok:1.18.48")
annotationProcessor("org.projectlombok:lombok:1.18.48")
annotationProcessor("io.github.graphmaquina:graphmaquina-processor:$graphmaquinaVersion")
```

Do not duplicate the GraphMaquina entry already present in the build. Lombok supplies accessors; it does not
replace GraphMaquina's generator or annotation processor.

### Source sets and generator selection

Generation participates in a source set only when that source set declares the GraphMaquina processor. Main
uses `annotationProcessor`; test uses `testAnnotationProcessor`. Tests that only call already generated main
facades normally do not need their own processor.

For graph declarations under `src/test/java`, add to the same `dependencies` block:

```kotlin
testAnnotationProcessor("io.github.graphmaquina:graphmaquina-processor:$graphmaquinaVersion")
```

The build script above compiles every source set under `-Xlint:all` and `-Werror`. The processor claims only
GraphMaquina's own annotations, so in a test source set that declares it, javac warns that no processor claimed
JUnit's `@Test`, and that warning fails `compileTestJava`. Turn javac's `processing` lint off there:

```kotlin
tasks.compileTestJava {
    options.compilerArgs.add("-Xlint:-processing")
}
```

If test entities use Lombok, add its test compile-only and annotation-processor dependencies, preserving order.

| Task | Purpose |
| --- | --- |
| `generateStateGraphApi` | Generate the main source set vocabulary |
| `generateTestStateGraphApi` | Generate test vocabulary if test participates |
| `generate<SourceSet>StateGraphApi` | Generate vocabulary for a custom source set |
| `compileJava` / `compileTestJava` | Run generation and the verified compilation lifecycle for participating scopes |

The plugin resolves the generator version matching its own version. Advanced overrides use:

```kotlin
graphmaquina {
    generatorVersion.set("x.y.z")  // the version you installed from a checkout
}
```

An explicit dependency on `graphmaquinaGenerator` replaces the default dependency. The repository example uses
this to run the sibling codegen project. Use matching versions for every GraphMaquina artifact.

## Configure Maven

This is a complete alternative build for the same ticket source files. Save it as `pom.xml` in
`tickets-demo`. It includes all six lifecycle executions and distinct main/test manifest arguments.
Run the same [install command](#configure-gradle) first: Maven reads `~/.m2/repository` as its local
repository, so it resolves what that command wrote with no repository declaration in this POM. Every
GraphMaquina artifact comes from the single version the `graphmaquina.version` property sets. For an
internal repository, configure both dependency and plugin repositories as shown in
[the build guide](typed-annotations-build.md#where-the-artifacts-come-from).

### pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>tickets-demo</artifactId>
  <version>1.0.0</version>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <!-- the version you installed from a checkout -->
    <graphmaquina.version>x.y.z</graphmaquina.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.github.graphmaquina</groupId>
      <artifactId>graphmaquina-core</artifactId>
      <version>${graphmaquina.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.15.0</version>
        <configuration>
          <release>${maven.compiler.release}</release>
          <annotationProcessorPaths>
            <path>
              <groupId>io.github.graphmaquina</groupId>
              <artifactId>graphmaquina-processor</artifactId>
              <version>${graphmaquina.version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
        <executions>
          <execution>
            <id>default-compile</id>
            <configuration>
              <compilerArgs>
                <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/annotation-manifest.json</arg>
                <arg>-Xlint:all,-serial</arg>
                <arg>-Werror</arg>
              </compilerArgs>
            </configuration>
          </execution>
          <execution>
            <id>default-testCompile</id>
            <configuration>
              <compilerArgs>
                <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/test/annotation-manifest.json</arg>
                <arg>-Xlint:all,-serial,-processing</arg>
                <arg>-Werror</arg>
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
          <execution>
            <id>graphmaquina-generate</id>
            <phase>generate-sources</phase>
            <goals><goal>generate</goal></goals>
          </execution>
          <execution>
            <id>graphmaquina-prepare</id>
            <phase>process-sources</phase>
            <goals><goal>prepare</goal></goals>
          </execution>
          <execution>
            <id>graphmaquina-complete</id>
            <phase>compile</phase>
            <goals><goal>complete</goal></goals>
          </execution>
          <execution>
            <id>graphmaquina-generate-test</id>
            <phase>generate-test-sources</phase>
            <goals><goal>generate-test</goal></goals>
          </execution>
          <execution>
            <id>graphmaquina-prepare-test</id>
            <phase>process-test-sources</phase>
            <goals><goal>prepare-test</goal></goals>
          </execution>
          <execution>
            <id>graphmaquina-complete-test</id>
            <phase>test-compile</phase>
            <goals><goal>complete-test</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

The test compilation turns javac's `processing` lint off. `<annotationProcessorPaths>` applies to both
compilations, so the GraphMaquina processor runs over the test sources too, and it claims only GraphMaquina's own
annotations: under `-Xlint:all`, javac warns that no processor claimed JUnit's `@Test`, and `-Werror` fails the
test compilation as soon as it holds a test. The main compilation keeps the lint, since the processor claims every
annotation the ticket's main sources carry; turn it off there too once they carry annotations no processor claims,
such as Spring's or Jakarta's.

Run from the application directory:

```bash
mvn --version
mvn clean verify
```

To run the quickstart after Maven has resolved the library and compiled the application, let Maven write the
dependency classpath and put the compiled classes in front of it:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" com.example.tickets.App
```

The generated file holds absolute paths into your local repository, wherever that is; on Windows, use `;` as
the Java classpath separator.

For Lombok, add `org.projectlombok:lombok:1.18.48` as a `provided` dependency and place its `<path>` before the
GraphMaquina processor's `<path>`. Unlike a Spring Boot parent, this standalone POM does not supply dependency
versions implicitly.

### Maven lifecycle and constraints

| Goal | Phase | Responsibility |
| --- | --- | --- |
| `generate` | `generate-sources` | Generate vocabulary and add the main generated source root |
| `prepare` | `process-sources` | Retire previous owned compilation outputs |
| `complete` | `compile`, after javac | Publish accepted exports and seal ownership |
| `generate-test` | `generate-test-sources` | Generate test vocabulary |
| `prepare-test` | `process-test-sources` | Prepare test compilation |
| `complete-test` | `test-compile`, after test javac | Publish test exports and seal ownership |

Merely declaring the plugin does not activate these goals. Use the executions above. Invoking
`compiler:compile` directly skips the required preceding lifecycle; use `mvn compile`, `mvn test-compile` or
`mvn verify` instead.

The adapter validates the effective Java release, processor path, manifest paths and JDK alignment. It rejects
`-proc:none` or `-proc:only` as the last `-proc:` option javac gets, whether from `proc` or from a compiler
argument, `failOnError=false`, `useIncrementalCompilation=false`, missing processor configuration, a
`-processor` list or `<annotationProcessors>` that leaves out
`io.github.graphmaquina.processor.StateGraphProcessor` or holds a name that is not a valid qualified class name,
a processor path compiler argument that does not reach the processor, unless it still holds a `${` once the check
evaluates it, a `--processor-module-path`, a processor that is only a dependency when javac 23 or later is not asked
to process, debug settings that leave out the `SourceFile` attribute, classes or processor sources sent elsewhere
than the project keeps them, as `<outputDirectory>`, `<multiReleaseOutput>`, `-d` or `-s` can,
`<compileSourceRoots>` without the generated directory, divergent main/test manifests, compiler includes or
excludes, incremental excludes included, that leave out the compilation carrier the generator emits, and includes
that hold only empty values. It checks the execution of the compiler's `compile` or `testCompile` goal that the
default lifecycle runs and that compiles the generated vocabulary, whatever its id, so a build may bind
`default-compile` to the phase `none` and compile through an execution of its own, as `kotlin-maven-plugin` asks a
mixed Kotlin and Java build to do; declare `maven-compiler-plugin` before this plugin then, since an execution of
its own runs in declaration order within its phase, and the opposite order, which would run `complete` before that
javac, is refused. Two running executions that both compile the vocabulary are refused. A compilation the build
switches off is skipped instead: `maven.main.skip` or `<skipMain>` makes the three main goals log that and do
nothing, `maven.test.skip` or `<skip>` the three test goals, a compilation the default lifecycle runs no execution
of the compiler's goal for, as when that goal is bound only to `none` or to phases of other lifecycles, is skipped
the same way, and a project packaged as `pom` skips all six. Run the normal lifecycle when generating graphs.

| Optional property | Meaning |
| --- | --- |
| `graphmaquina.javaExecutable` | Explicit `java` executable for generation. It must belong to a JDK 21 or later, the one javac compiles with: the two are compared by JDK home after symbolic links are followed, so a wrapper launcher outside that JDK's `bin` directory is refused. On Windows, a name without an extension that names no file stands for the `.exe` beside it |
| `graphmaquina.compilationId` | Override compilation identity; defaults distinguish artifact and main/test scope |

See the [Maven adapter reference](typed-annotations-build.md#maven) for toolchains and custom compiler paths.

## Quickstart: a complete ticket machine

Create the following seven files under `src/main/java/com/example/tickets/`. They are shared by the Gradle and
Maven configurations above and require no Lombok, framework or external service. You can write all seven files
before the first build; [the generation walkthrough](#how-your-flow-annotations-are-generated) explains why
the references to generated annotations are valid in this workflow.

A ticket is submitted for review, and submission registers it with the review board. A board that will not
take the ticket is a declared domain failure, so the ticket lands in `FAILED`. Once in review it can be
approved by a person, auto-approved by a rule, retouched in place, or rejected back to `OPEN`.

![Ticket workflow: SUBMIT moves OPEN to REVIEW when hasTitle passes, or commits FAILED through the DECLINE route. From REVIEW, APPROVE and AUTO_APPROVE are two separate edges to DONE, RETOUCH is a self-loop and REJECT returns to OPEN.](images/ticket-workflow.svg)

### TicketState.java

```java
package com.example.tickets;

public enum TicketState {
    OPEN, REVIEW, DONE, FAILED
}
```

### TicketTransition.java

```java
package com.example.tickets;

public enum TicketTransition {
    SUBMIT, APPROVE, AUTO_APPROVE, RETOUCH, REJECT, DECLINE
}
```

Two enums, never one. `TicketState` names where a ticket can be; `TicketTransition` names the edges it can
take. You write both: GraphMaquina derives no identifier from an action name, a method name or a destination.

### Ticket.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Objects;

public final class Ticket {
    @GraphProperty
    private final String id;

    @GraphProperty
    private TicketState state = TicketState.OPEN;

    private final String title;
    private final boolean accepted;

    public Ticket(String id, String title, boolean accepted) {
        this.id = Objects.requireNonNull(id);
        this.title = Objects.requireNonNull(title);
        this.accepted = accepted;
    }

    public String getId() { return id; }
    public TicketState getState() { return state; }
    public String getTitle() { return title; }
    public boolean isAccepted() { return accepted; }

    public void setState(TicketState state) {
        this.state = Objects.requireNonNull(state);
    }
}
```

The state starts initialized. `initial = OPEN` describes the graph; it does not initialize new entities.
The runtime calls the state setter during commit. Action code must not call it.

### TicketActions.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.annotation.GraphHook;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import java.util.ArrayList;
import java.util.List;

public final class TicketActions {
    private final List<String> decisions = new ArrayList<>();

    /** Which action actually ran, in order. Two edges to DONE never stand in for each other. */
    public List<String> decisions() {
        return List.copyOf(decisions);
    }

    @GraphGuard
    public GuardResult hasTitle(GuardContext<Ticket, TicketState, TicketTransition> ctx) {
        return ctx.entity().getTitle().isBlank()
                ? GuardResult.reject("a ticket needs a title")
                : GuardResult.pass();
    }

    @GraphAction
    public ActionOutcome register(TransitionContext<Ticket, TicketState, TicketTransition> ctx) {
        return ctx.entity().isAccepted()
                ? ActionOutcome.ok()
                : ActionOutcome.failed("the review board declined the ticket");
    }

    @GraphAction
    public ActionOutcome approve(TransitionContext<Ticket, TicketState, TicketTransition> ctx) {
        decisions.add("approve " + ctx.entity().getId());
        return ActionOutcome.ok();
    }

    @GraphAction
    public ActionOutcome autoApprove(TransitionContext<Ticket, TicketState, TicketTransition> ctx) {
        decisions.add("autoApprove " + ctx.entity().getId());
        return ActionOutcome.ok();
    }

    @GraphAction
    public ActionOutcome retouch(TransitionContext<Ticket, TicketState, TicketTransition> ctx) {
        decisions.add("retouch " + ctx.entity().getId());
        return ActionOutcome.ok();
    }

    @GraphHook
    public void committed(Ticket ticket, CommitContext<TicketState, TicketTransition> commit) {
        System.out.println("Committed " + ticket.getId() + ": " + commit.from()
                + " -" + commit.transition() + "-> " + commit.to());
    }
}
```

### Tickets.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

@GenerateGraphApi
@TicketsGraph.Definition(initial=TicketState.OPEN,
    terminals={TicketState.DONE, TicketState.FAILED},
    allowsCycle={TicketState.OPEN, TicketState.REVIEW},
    state=TicketRefs.State.class, id=TicketRefs.Id.class,
    afterCommit=TicketActionsRefs.Committed.class)
@TicketsGraph.StatePolicy(state=TicketState.OPEN, otherwise="submit the ticket first")
@TicketsGraph.StatePolicy(state=TicketState.REVIEW, otherwise="approve, reject or retouch the ticket")
@TicketsGraph.Reject(from=TicketState.OPEN, transition=TicketTransition.APPROVE,
    reason="approval requires review")
@TicketsGraph.Transition(id=TicketTransition.DECLINE, from=TicketState.OPEN, to=TicketState.FAILED)
public interface Tickets extends Machine<Ticket,TicketState,TicketTransition> {

    @TicketsGraph.Transition(id=TicketTransition.SUBMIT, from=TicketState.OPEN,
        to=TicketState.REVIEW, action=TicketActionsRefs.Register.class,
        guards=TicketActionsRefs.HasTitle.class, onFailure=TicketTransition.DECLINE)
    Outcome<Ticket,TicketState,TicketTransition> submit(Ticket ticket);

    @TicketsGraph.Transition(id=TicketTransition.APPROVE, from=TicketState.REVIEW,
        to=TicketState.DONE, action=TicketActionsRefs.Approve.class)
    Outcome<Ticket,TicketState,TicketTransition> approve(Ticket ticket);

    @TicketsGraph.Transition(id=TicketTransition.AUTO_APPROVE, from=TicketState.REVIEW,
        to=TicketState.DONE, action=TicketActionsRefs.AutoApprove.class)
    Outcome<Ticket,TicketState,TicketTransition> autoApprove(Ticket ticket);

    @TicketsGraph.Transition(id=TicketTransition.RETOUCH, from=TicketState.REVIEW,
        to=TicketState.REVIEW, action=TicketActionsRefs.Retouch.class)
    Outcome<Ticket,TicketState,TicketTransition> retouch(Ticket ticket);

    @TicketsGraph.Transition(id=TicketTransition.REJECT, from=TicketState.REVIEW, to=TicketState.OPEN)
    Outcome<Ticket,TicketState,TicketTransition> reject(Ticket ticket);
}
```

`APPROVE` and `AUTO_APPROVE` are two edges between the same pair of states, each with its own action. They
are parallel edges, not duplicates: `approve(ticket)` can never run `autoApprove`'s action, and no
deduplication by destination collapses them. `RETOUCH` is a self-loop, an ordinary edge that happens to end
where it started, and `REJECT` closes the `OPEN → REVIEW → OPEN` loop, so both states appear in
`allowsCycle`. `DECLINE` has no method of its own: it is the route `SUBMIT` names in `onFailure`, and it is
resolved from `SUBMIT`'s origin.

`TicketsGraph`, `TicketRefs` and `TicketActionsRefs` do not exist in handwritten source. The generator writes
them before javac compiles this interface, using `@GenerateGraphApi`, `Machine<Ticket, TicketState, TicketTransition>`
and the marked members. See [the first-generation walkthrough](#how-your-flow-annotations-are-generated) for the
minimal-interface and complete-source workflows. Do not create placeholder versions of these classes.

### TicketMachines.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

@StateGraphs
public interface TicketMachines extends Machines {
    Tickets tickets();
}
```

The processor generates `TicketsRuntime` and `TicketMachinesRuntime`. The set owns the runtime machines
and closes their default executors together.

### App.java

```java
package com.example.tickets;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import java.util.List;
import java.util.Optional;

public final class App {
    private App() { }

    public static void main(String[] args) {
        TicketActions actions = new TicketActions();
        try (TicketMachines machines = TicketMachinesRuntime.builder(actions).build()) {
            Tickets tickets = machines.tickets();

            Ticket accepted = new Ticket("T-1", "Document the workflow", true);
            require(tickets.submit(accepted).status() == OutcomeStatus.APPLIED, "submit should apply");
            require(tickets.approve(accepted).status() == OutcomeStatus.APPLIED, "approve should apply");
            require(accepted.getState() == TicketState.DONE, "an approved ticket should be DONE");

            Ticket untitled = new Ticket("T-2", "", true);
            require(tickets.submit(untitled).code() == OutcomeCode.GUARD_REJECTED, "blank title should reject");
            require(untitled.getState() == TicketState.OPEN, "a rejected ticket should remain OPEN");

            Ticket refused = new Ticket("T-3", "Await the board", false);
            Outcome<Ticket, TicketState, TicketTransition> declined = tickets.submit(refused);
            require(declined.code() == OutcomeCode.FAILURE_ROUTED
                    && declined.explanation().equals(Optional.of("the review board declined the ticket")),
                    "a decline should be handled");
            require(refused.getState() == TicketState.FAILED, "the failure route should commit FAILED");

            Ticket fresh = new Ticket("T-4", "Not submitted yet", true);
            Outcome<Ticket, TicketState, TicketTransition> refusal = tickets.approve(fresh);
            require(refusal.code() == OutcomeCode.NO_TRANSITION
                    && refusal.explanation().equals(Optional.of("approval requires review")),
                    "APPROVE leaves no edge from OPEN");

            Ticket revised = new Ticket("T-5", "Revise the wording", true);
            tickets.submit(revised);
            require(tickets.retouch(revised).status() == OutcomeStatus.APPLIED, "a self-loop should apply");
            require(revised.getState() == TicketState.REVIEW, "a self-loop ends where it started");
            require(tickets.reject(revised).status() == OutcomeStatus.APPLIED, "reject should apply");
            require(revised.getState() == TicketState.OPEN, "reject returns the ticket to OPEN");

            Ticket automatic = new Ticket("T-6", "Trivial typo fix", true);
            tickets.submit(automatic);
            require(tickets.autoApprove(automatic).status() == OutcomeStatus.APPLIED, "autoApprove should apply");
            require(actions.decisions().equals(List.of(
                    "approve T-1", "retouch T-5", "autoApprove T-6")),
                    "each operation runs its own action: " + actions.decisions());
            System.out.println("All ticket scenarios passed.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
```

Expected application output:

```text
Committed T-1: OPEN -SUBMIT-> REVIEW
Committed T-1: REVIEW -APPROVE-> DONE
Committed T-3: OPEN -DECLINE-> FAILED
Committed T-5: OPEN -SUBMIT-> REVIEW
Committed T-5: REVIEW -RETOUCH-> REVIEW
Committed T-5: REVIEW -REJECT-> OPEN
Committed T-6: OPEN -SUBMIT-> REVIEW
Committed T-6: REVIEW -AUTO_APPROVE-> DONE
All ticket scenarios passed.
```

One `afterCommit` on `Definition` is the default hook of every edge, so each committed hop prints itself,
including the self-loop and the failure route. Nothing prints for `T-2`, whose guard rejected, or for `T-4`,
whose request had no edge: neither committed anything. The program checks an applied edge, a guard rejection,
a handled failure, a request with no edge, a self-loop, a cycle and two parallel edges. It does not rely on
Java assertions being enabled.

## Understand the graph

A machine is a directed graph. Its nodes are the constants of the state enum `S`, and its edges are the
transitions you declare. An edge is a triple:

```text
(from: S, transition: T, to: S)
```

`T` is the machine's own enum of transition identifiers, written by you and distinct from `S`. An edge is
uniquely keyed by `(from, transition)`: from one state, one identifier selects exactly one edge. Two
declarations of the same key are `SG102`, even when their guards differ; there is no priority and no
first-guard-wins.

Only declared transitions become edges. The ticket machine above expresses its whole intent in six edges:

| From | Identifier | To | Kind |
| --- | --- | --- | --- |
| `OPEN` | `SUBMIT` | `REVIEW` | `ACTION` `register`, guard `hasTitle`, `onFailure = DECLINE` |
| `OPEN` | `DECLINE` | `FAILED` | `MOVE`, declared on the type: the failure route, no facade method |
| `REVIEW` | `APPROVE` | `DONE` | `ACTION` `approve` |
| `REVIEW` | `AUTO_APPROVE` | `DONE` | `ACTION` `autoApprove` |
| `REVIEW` | `RETOUCH` | `REVIEW` | `ACTION` `retouch`, a self-loop |
| `REVIEW` | `REJECT` | `OPEN` | `MOVE` |

Every edge is `ACTION` or `MOVE`, and that is the whole of `TransitionKind`: whether the edge runs an action
before it commits. Both are real edges that change the entity's state and run the effective hook.

### Identity, not destination

An operation names a transition, never a target state. `machines.tickets().approve(ticket)` compiles to
`stateMachine.execute(ticket, TicketTransition.APPROVE)`, and the engine resolves `(current state, APPROVE)`
once. If the resolution finds no edge, the request is refused. The engine never looks for another edge that
happens to reach the same place.

This is what makes `APPROVE` and `AUTO_APPROVE` two different things even though both end in `DONE`:

```java
Outcome<Ticket, TicketState, TicketTransition> outcome = tickets.autoApprove(ticket);
// Applied, to = DONE, and TicketActions.autoApprove ran. approve's action did not.
```

The same identifier may leave several states, with different destinations if the domain says so; the current
state disambiguates. The shop's `Items` machine declares one `CANCEL` from `PENDING` and from `PACKING`, both
reaching `CANCELLED`, and the order's cancellation cascade asks for `ItemTransition.CANCEL` rather than for
the `CANCELLED` state:

```java
ctx.cascadeAll(Items.class, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.COLLECT);
```

`onFailure`, watch rules and cascades all name identifiers for the same reason. A route is the edge you named,
resolved from the origin that needs it.

### Absence, self-loops and reevaluation

Three situations stay separate here, and each has its own
observable result.

| Situation | What the engine does | Outcome |
| --- | --- | --- |
| No edge is keyed by `(current, requested)` | Nothing runs. No guard, no action, no state write. | `Rejected` with `RejectionKind.NO_TRANSITION`; a sub-machine whose parent does not hold it is `DETACHED` first |
| An explicit self-loop `A --T--> A` is requested | The edge runs like any other: guards, action, commit, hook. | `Applied`, with `recordedState() == A` |
| `reevaluate(entity)` finds no watch, or no rule that holds | Nothing runs. | `Ignored`, with `IgnoreReason.NO_WATCH` or `NO_RULE_MATCHED` |

A self-loop is an ordinary edge that ends where it started. It needs `allowsCycle` on that state, it may be
the only exit of a non-terminal state, and it is forbidden on a terminal. Requesting a transition while the
entity already sits in that transition's destination is not special in any way: either an edge is keyed by
`(current, requested)` and it runs, or none is and the request is refused.

`Ignored` is reserved for reevaluation. There is no implicit staying, and no operation is silently ignored
because the entity is already somewhere convenient. If the intent is to do nothing, do not call the
operation.

### Guards and the reason a request is refused

A guard belongs to an edge that exists. It runs after the edge is resolved and before its action, in
declaration order, stopping at the first rejection:

```java
assertThat(orders.retry(order)).isRejected().isRejectedByGuardNamed("retryBudgetLeft");
```

A refusal for lack of an edge is a different kind, and carries no guard:

```java
assertThat(orders.ship(order)).isRejected()
        .hasReason("cannot ship without payment")
        .hasNoTransition();
```

`RejectionKind` is what tells the two apart. `Rejected` reports the entity, the origin, the identifier that
was requested, the reason and — for `GUARD` only — the name of the guard. It reports no destination: the
same identifier reaches different states from different origins, so there is no single `to` to name.

### Policies: every node answers

Rejections are answers a node gives, not edges. Every state carries a `StatePolicy<S, T>` with a non-blank
`otherwise` reason, and optionally a non-blank reason per identifier:

```java
@TicketsGraph.StatePolicy(state=TicketState.OPEN, otherwise="submit the ticket first")
@TicketsGraph.Reject(from=TicketState.OPEN, transition=TicketTransition.APPROVE,
    reason="approval requires review")
```

`Reject` overrides `otherwise` for one identifier from one state. Declaring both an edge and a rejection for
the same `(from, transition)` is `SG702`; a state with no policy is `SG701`. A terminal that declares none
gets the stable text `terminal state <NAME> accepts no transitions`, which an explicit `StatePolicy`
replaces.

A policy is not a universe of destinations that must be "closed". You never enumerate what a state cannot do.
You declare what it can do, and the policy words the answer for everything else.

### Structural rules

- Every state must be reachable from `initial` through declared edges.
- A terminal has no outgoing edge at all, including a self-loop. A non-terminal must have at least one,
  which may be a self-loop.
- Every cycle must pass through at least one state listed in `allowsCycle`, and an allowance for a state that
  belongs to no cycle is rejected too. A self-loop is a cycle.
- An `onFailure` identifier, and the recovery identifier of every recovery rule, must name a different edge that
  exists from the failing edge's own origin. Such an edge may legitimately reach the same destination as the edge
  it recovers, because identity, not destination, tells them apart.
- Every watch rule must name an identifier with an edge from the watched state. A terminal cannot watch.
- A state that holds a sub-machine maps each terminal state of the child, and no other state, to exactly one exit:
  an identifier with an edge from that state that no `onFailure`, recovery rule or watch rule of that state names,
  and, with annotations, that no operation of the facade executes. A terminal state holds no sub-machine, another
  state holds at most one, and a machine's sub-machines never contain the machine again.

Unauthorised cycles are reported as one witness per cyclic strongly connected component rather than one
message per elementary cycle. The rule about what is accepted is unchanged; the number of messages is
deliberately smaller, and the witnesses are deterministic.

For example, the ticket machine permits `OPEN → REVIEW → OPEN` and the `RETOUCH` self-loop by listing both
states in `allowsCycle`. The shop permits `NEW → PAYMENT_DECLINED → NEW` with `allowsCycle = PAYMENT_DECLINED`.
Guards still decide when another lap is permitted; the allowance only says the loop is intentional.

### Operations and internal edges

A public operation has this shape:

```java
Outcome<Ticket, TicketState, TicketTransition> approve(Ticket ticket);
```

It carries one or more `Transition` annotations from its own generated vocabulary, all with the same `id`.
Several annotations let one identifier open branches from different origins — that is how the shop's
`cancel(order, cancellationId)` covers `NEW` and `PAYMENT_DECLINED` in a single method. Two methods cannot claim
the same identifier, and a type-level `Transition` may add further branches of an identifier a method owns as
long as the keys stay disjoint; `SG703` reports both mistakes.

A `Transition` on the interface itself declares an edge with no facade method. It is how failure routes,
cascade-only and watch-only edges are declared: `DECLINE` in the quickstart and `PAYMENT_DECLINED` in the
shop are exactly that. Omitting `action` makes the edge a `MOVE`.

A typed facade intentionally exposes no generic selector by destination. The application's entry points are
the operations you declared, plus `reevaluate`.

An operation may accept a second argument: `Outcome<Entity, Status, Change> screen(Entity entity, Verdict verdict)`.
Guards and actions read it through `ctx.input(Verdict.class)`, which returns `Optional<Verdict>`. This lets
a guard decide from a fact the caller has already obtained without storing a temporary argument on the
entity or calling a service from the guard. Use an immutable record for related input fields; a Java file
has only one public top-level type, so it gets its own file:

```java
// Verdict.java
public record Verdict(boolean eligible) { }
```

A declaration like this belongs on the machine interface, beside its other operations — the same place as
`approve` in this quickstart's `Tickets.java` — and its guard belongs on the bindings host, beside its other
guards, the same place as `hasTitle` in `TicketActions.java`. The snippet below declares its own screening
vocabulary rather than the ticket one; adapt the graph annotation and the `Refs` class — following the
naming rule under [Generated names](#generated-names) — and the states and identifiers to your own machine:

```java
@ScreeningGraph.Transition(id = Change.SCREEN, from = NEW, to = SCREENED,
        guards = RulesRefs.Eligible.class)
Outcome<Entity, Status, Change> screen(Entity entity, Verdict verdict);
```

```java
@GraphGuard
public static GuardResult eligible(GuardContext<Entity, Status, Change> ctx) {
    return ctx.input(Verdict.class)
            .map(verdict -> verdict.eligible() ? GuardResult.pass() : GuardResult.reject("ineligible"))
            .orElseGet(() -> GuardResult.reject("missing verdict"));
}
```

`transitionsFrom(state)` returns the identifiers declared from a state. It is structural inspection: it runs
no guard and promises nothing about a particular entity. To see where those identifiers lead, read
`definition().outgoingFrom(state)`, and for one edge, `definition().findTransition(from, id)`.

Upgrading an application written against the 1.x transition matrix is a separate document:
[migrating to 2.0](migration-2.0.md) maps every retired contract to its replacement.

## Annotation and binding reference

### Handwritten markers

Import these from `io.github.graphmaquina.annotation`:

| Annotation | Place it on | Purpose |
| --- | --- | --- |
| `@GenerateGraphApi` | Top-level `Machine<E, S, T>` interface | Generate its typed graph vocabulary |
| `@StateGraphs` | Top-level interface extending `Machines` | Generate a set and builder for its machine accessors |
| `@GraphProperty` | Entity field or getter | Export a property symbol |
| `@GraphAction` | Action method | Export an action binding |
| `@GraphGuard` | Guard method | Export a guard binding |
| `@GraphHook` | Hook method | Export an after-commit binding |
| `@GraphCondition` | Watch-condition method | Export a custom watch predicate |
| `@GraphLookup` | Parent-lookup method | Export the lookup that finds the parent of a sub-machine's entity |
| `@GraphBindings` | Concrete binding host | Export inherited marked members through this concrete host |
| `@GraphPeers` | Machine interface that watches or binds other machines | Request typed vocabulary for its declared owner/peer pairs |
| `@Timeout` | Nested annotation element | Express seconds and nanoseconds for an asynchronous action timeout |

Markers identify exports; marking a method alone does not attach it to a transition. Reference its generated
symbol from the graph declaration.

### Generated names

| Handwritten declaration | Generated API |
| --- | --- |
| `Tickets extends Machine<Ticket, TicketState, TicketTransition>` | `TicketsGraph.Definition`, `.StatePolicy`, `.StatePolicies`, `.Transition`, `.Transitions`, `.Recovery`, `.Reject`, `.Rejects` |
| Property `Ticket.state` | `TicketRefs.State.class` |
| Marked method `TicketActions.hasTitle` | `TicketActionsRefs.HasTitle.class` |
| `@GraphPeers(Items.class)` on `Orders` | `OrdersItems.Watch`, `.Watches`, `.When`, `.Submachine`, `.Submachines` and `.Exit`, in the owner's package |
| Machine `Tickets` | `TicketsRuntime`, including `KEY`, graph construction and facade implementation |
| Set `TicketMachines` | `TicketMachinesRuntime`, including the generated builder |

`KEY` is a `MachineKey<E, S, T>`: entity class, state enum and transition enum. Its equality and hash include
all three, and a registry refuses two machines that write the same entity and state enum through different
transition catalogues.

Generated type names use PascalCase with no underscores. Underscores and nesting dots separate words:
`_id` → `Id`, `has_title` → `HasTitle`, and `Outer.Inner` → `OuterInnerRefs`. Existing internal case and
uppercase acronyms are preserved: `hasTitle` → `HasTitle`, `URL` → `URL`. `Graph` and `Refs` retain their roles.
Original Java declarations and invocation names remain unchanged. If normalization merges two names, generation
fails with `SG603` naming both sources: `id` / `_id` and `authorize` / `Authorize` conflict within one references
container. A conflict with the container itself also fails; there are no underscore escapes or opaque suffixes.
Names that cannot produce a valid nonempty Java identifier are rejected. Two generated names that differ only in
case, such as the symbols `ApproveId` / `ApproveID` of the members `approveId` / `approveID`, the containers
`OuterInnerRefs` / `OuterinnerRefs` of the hosts `Outer.Inner` / `Outerinner` or the companions `LibraryRuntime` /
`LIBRARYRuntime` of the sets `Library` / `LIBRARY`, fail with `SG603` where a volume that holds their files does not
tell names apart by case, since it would keep one file of the two.

A generated symbol is a compile-time reference. It is not an action instance, a dependency-injection bean or
a reflective lookup. The facade calls the selected declaration directly, preserving the marked overload.

### Generated graph annotation elements

In this table, `S` is the machine's state enum, `T` its transition enum and `P` a property symbol class.

| Annotation | Target | Required elements | Optional elements |
| --- | --- | --- | --- |
| `Definition` | type | `initial: S`, `state: P`, `id: P` | `terminals: S[]`, `allowsCycle: S[]`, `afterCommit: HookSymbol` |
| `StatePolicy` | type | `state: S`, `otherwise: String` | None |
| `Transition` | type, method | `id: T`, `from: S[]`, `to: S` | `action`, `guards`, `onFailure: T`, `failsOn`, `recoveries: Recovery[]`, `timeout`, `afterCommit` |
| `Recovery` | none, nested in `Transition.recoveries` | `kind: Class<? extends FailureKind>`, `transition: T` | `failsOn` |
| `Reject` | type | `from: S`, `transition: T`, `reason: String` | None |

`StatePolicy`, `Transition` and `Reject` are repeatable, through the generated `StatePolicies`, `Transitions` and
`Rejects` containers. `from` must contain at least one state. Guard arrays execute in declaration order. `failsOn` takes
throwable class literals. An absent `action` makes the edge a `MOVE`; `onFailure`, `failsOn`, `recoveries` and
`timeout` require an action, and `timeout` requires an asynchronous one.

The processor reads and validates `recoveries`, including their failure kinds, throwable types and recovery
transitions. Generated companions emit one typed `recover(...)` call per rule in declaration order, so annotated
facades route failures through those rules just as graphs built directly with the DSL do.

Java annotations do not have nullable elements, so the API represents optional single values as arrays.
`onFailure`, `timeout`, `afterCommit`, `Transition.action`, custom `When.condition`, and the `child`, `parent` and
`parentLookup` of a `Submachine` accept zero or one value. Use the single-value syntax, for example
`onFailure = TicketTransition.DECLINE`, or omit the element. Multiple values are `SG607`.

### Properties and accessors

For a field marked `@GraphProperty`, the processor looks for public instance readers in this order:
`property()`, `getProperty()`, `isProperty()`. It searches for state writers as `setProperty(value)` and then
`property(value)`. A field marker does not grant access to the field and does not generate an accessor.

A marker on a getter selects that getter directly. Its logical property name follows JavaBeans
normalization: `getState()` exports `state`, `items()` exports `items`, and `getURL()` exports `URL`.

| Property use | Required shape |
| --- | --- |
| `Definition.state` | Public instance reader returning the machine's state enum; compatible public instance writer |
| `Definition.id` | Public instance reader returning an identity value; values must be non-null at runtime |
| `Watch.peers` | Owner reader returning `Collection<? extends PeerEntity>` or a subtype, such as `List<PeerEntity>`; a raw collection is refused |
| `Watch.owner` | Peer reader returning `Optional<OwnerEntity>` or an owner entity, never `null` |
| `Submachine.child` | Parent reader returning `Optional<ChildEntity>` |
| `Submachine.parent` | Child reader returning `Optional<ParentEntity>` |

Use `Optional.empty()` for a peer with no owner: an `owner` reader that returns the owner entity itself declares that
every peer has one. Keep relationship accessors consistent and never return null: a `peers` reader, an `owner`
reader, whichever type it declares, and any other `Optional` reader that returns `null` are refused, as
[triggering and observing watches](#triggering-and-observing-watches) and
[finding the parent with a lookup](#find-the-parent-with-a-lookup) say. If the setter overloads are ambiguous, the
processor reports the ambiguity instead of choosing based on declaration order.

### Method signatures

Public methods must match the role where the graph uses them. `E/S/T` refer to the owner entity, state and
transition types; `P/Q/U` refer to the peer's: the watched machine's for a condition, the sub-machine's for a lookup.

| Marker | Supported signature |
| --- | --- |
| `@GraphAction` | `ActionOutcome method(TransitionContext<E, S, T> ctx)` |
| `@GraphAction`, async | `CompletionStage<ActionOutcome> method(TransitionContext<E, S, T> ctx)` |
| `@GraphGuard` | `GuardResult method(GuardContext<E, S, T> ctx)` |
| `@GraphGuard`, async | `CompletionStage<GuardResult> method(GuardContext<E, S, T> ctx)` |
| `@GraphHook` | `void method(E entity)` or `void method(E entity, CommitContext<S, T> context)` |
| `@GraphCondition` | `boolean method(E owner, Collection<? extends P> peers, PeerView<P, Q, U> view)` |
| `@GraphLookup` | `Optional<E> method(P child)` |

A raw `CompletionStage` is no asynchronous result: an action or a guard that returns one is refused where it is
declared (`SG604`).

Static bindings are called statically. Instance bindings become constructor/builder dependencies. For example,
using three instance members of `OrderActions` adds one `OrderActions` dependency, not three instances. A class
used only through static members needs no builder argument.

To expose inherited marked members under a concrete host, use `@GraphBindings` on that host. The processor
checks the inherited signature with the concrete generic arguments substituted. Only what the host inherits is
exported through it, since a static binding is called through the host: a static method of an interface only through
the interface's own symbol, and a static method that the host or a superclass between hides with a method of the same
name and parameter types not at all, the hiding method being exported in its place when it is marked. Generated output and JAR
exports are checked for collisions; do not manually create classes in generated namespaces or edit symbols.

## Actions, guards and failure routes

The ticket quickstart shows synchronous actions and a guard. Actions receive `TransitionContext<E, S, T>`:

| Method | Meaning |
| --- | --- |
| `entity()` | The entity this step moves: the object supplied by the caller at the root, the peer in a cascaded step, the parent in the exit of a sub-machine |
| `transition()` | The identifier that selected this edge |
| `from()` / `to()` | The endpoints of the edge being executed |
| `depth()` | `0` for the root, increasing through cascades |
| `peer(...)` | Read another registered machine through a `PeerView` |
| `cascade(...)` | Request a peer transition, by identifier, within this unit of work |
| `cascadeAll(...)` | Apply a cascade to a collection using the selected policy |
| `input()` / `input(Class)` | The operation's second argument, if the caller passed one |
| `flow()` | The call's flow: typed values every step of the call shares, and the entity the call was made for; see [the call's flow](#the-calls-flow) |

Guards receive `GuardContext<E, S, T>`, which exposes the entity, the identifier, both endpoints, read-only
peer access, the same `input` accessors and a read-only `flow()`. It has no cascade API. Return `GuardResult.pass()` or
`GuardResult.reject("reason")`; do not return null. A rejecting guard stops the edge before its action runs
and produces `Rejected` with `RejectionKind.GUARD` and the guard's name. The guards of one edge have distinct names, so
the name tells which one rejected: a generated guard is named by its member's simple name, and two guards of one name
on an edge are refused with `SG105`. Guard code should be free of mutations and external side effects.

Without an input the optional is empty. A null input is refused before the call starts, as a null entity is, with a
`NullPointerException` that names the parameter. A value of an incompatible class throws `ClassCastException`;
absence and a wrong type are different conditions. Primitive operation arguments are boxed; read them with wrapper
tokens such as `Integer.class`. Class tokens cannot validate erased generic arguments. Input operations do not
support method type parameters or varargs.

The same input reaches the transition's guards, action and handled failure action. Cascades, the exits of
sub-machines, watch reactions and reevaluation have no input of their own and receive an empty optional. A shared
guard must handle that absence. An action can copy its input into [the call's flow](#the-calls-flow) for the
cascades, exits and hooks of the same call. Hooks, observers and transition records do not receive the input itself.

### Domain failures

An action returns `ActionOutcome.ok()`, `ActionOutcome.failed("reason")`, or `ActionOutcome.failed(kind, "reason")`
with a failure kind, a final class or record that implements `FailureKind`. A failed action is a handled business
result when a recovery rule of the edge declares its failure kind, which selects that rule's recovery identifier, or
else when the edge names an `onFailure` identifier, which takes every other returned failure and keeps its kind in
`Outcome.Failed.failureKind()`. Without either, the runtime throws `UnhandledFailureException` and does not commit the
planned state changes.

A failure route is an ordinary edge, declared from the origin of the edge that may fail. In the quickstart:

```java
@TicketsGraph.Transition(id=TicketTransition.DECLINE, from=TicketState.OPEN, to=TicketState.FAILED)
```

When `register` fails, the runtime discards the `SUBMIT` edge's pending changes, resolves `DECLINE` from
`OPEN` — the origin, never the destination it was heading for — and commits it. It returns `Outcome.Failed`,
whose `transition()` is `SUBMIT`, whose `to()` is `REVIEW`, the destination it did not reach, whose
`failureTransition()` is `DECLINE` and whose `movedTo()` is `FAILED`.

`onFailure` names an identifier, so a route may legitimately reach the same destination as the edge it
recovers: identity, not the destination, distinguishes them. Naming the failing edge's own key is `SG301`, as
is naming an identifier that has no edge from that origin.

The failure route can itself be an action. Its guards are bypassed when entered as failure handling, and its
own failure route is not chained. A failure or exception from that route aborts. Hooks apply to the edge that
actually committed, so the failing edge's hook does not run and the route's effective hook does.

### Map specific exceptions to a domain failure

This declaration is an excerpt from the shop's `Orders` machine:

```java
@OrdersGraph.Transition(id = OrderTransition.PAY, from = NEW, to = PAID,
    action = OrderActionsRefs.Charge.class,
    guards = OrderActionsRefs.RetryBudgetLeft.class,
    onFailure = OrderTransition.PAYMENT_DECLINED, failsOn = IOException.class,
    timeout = @Timeout(seconds = 5))
Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
```

`OrderTransition.PAYMENT_DECLINED` is an identifier that happens to share its spelling with the state
`OrderStatus.PAYMENT_DECLINED`. They are two enums, so javac keeps them apart, and the edge it names is
declared on the interface itself:

```java
@OrdersGraph.Transition(id = OrderTransition.PAYMENT_DECLINED, from = NEW, to = PAYMENT_DECLINED)
```

The corresponding async action calls a gateway and returns a stage. An exceptional completion with a matching
`IOException` uses the declared failure route. The runtime unwraps a `CompletionException` or
`ExecutionException` that carries a cause before classifying that cause. It unwraps exactly those two classes:
an exception of a subclass of either is classified as itself, not through its cause. It also classifies a
checked exception that an action throws like any other, whether the method declares it, so that an action
declared `throws IOException` with `failsOn = IOException.class` takes the failure route, or throws it without
declaring it, as Kotlin or `@SneakyThrows` code can. A guard, a hook, a watch condition, a lookup, a state or identity
accessor, a watch's peers or owner reader, or a sub-machine's child or parent reader may declare a checked exception
too: the generated companion compiles, and the exception is handled as the same exception thrown undeclared would be.
`failsOn` applies to action failures; a guard exception is a technical abort. A library exception, a
`StateMachineException`, that a guard or action throws or completes its stage with, bare or wrapped in one of those two
classes, is never matched against `failsOn`, whatever the edge lists: it aborts the call and becomes its cause, so a
failure of another call never passes for a failure of this one. A failure of the call itself, such as the abort a failed
cascade throws into the action, is the exception: the engine throws it as itself or, when the transaction is poisoned,
the failure that poisoned the transaction. An `Error` is never routed either: thrown as itself it propagates unchanged,
and one that completes a stage, or that one of those two classes carries, aborts.

`failsOn` cannot be a blanket `Throwable`, `Exception` or `RuntimeException`, an `Error`, a library engine exception, or
a `CancellationException` or one of its subtypes, which are never domain failures; nor `CompletionException` or
`ExecutionException` themselves, which are unwrapped before matching whenever they carry a cause, so declare the type of
their cause. All of these are `SG302`. Declare specific foreign throwable types, usually exceptions. Timeout,
interruption and cancellation are not a substitute for a returned domain failure. `@Timeout` is valid only for
asynchronous actions.

The generated companion declares each edge, from each of its origins, as one chained expression, with one call per
guard, `failsOn` class and recovery rule, and javac attributes a chain recursively, so the stack of the thread it runs
on bounds how long one can be. With JDK 21 on a thread with a 1 MiB stack, javac compiled an edge with 400 `failsOn`
classes and failed with a `StackOverflowError` on one with 800. A statement written by hand with the DSL is bounded
the same way, by every call it chains, every edge of the graph when one statement declares them all: on that thread,
one of 400 edges without actions, 803 calls, compiled, and one of 600 edges failed. Keep an edge, or such a statement,
to a few hundred calls.

The full action implementation is in
[OrderActions.java](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/order/OrderActions.java).

### Recover by failure kind and exception type

`onFailure` gives an edge a fallback recovery for admitted failures that no rule takes. When the failures of one
edge must end in different states, declare recovery rules. A rule names a failure kind and the identifier of its
recovery edge, and may list the throwable types that select it. `onFailure` stays the fallback for a failure that
no rule takes.

A failure kind is a type: a named final class or record that implements `io.github.graphmaquina.api.FailureKind`,
top-level or a member, and not an interface, an enum, a local or anonymous class, or a `Throwable`. The library
never creates an instance of it and keeps no catalogue of kinds. It compares the `Class`, so any edge of any
machine can use the same kind. One record per kind is enough:

```java
/** The states of a publication. */
public enum PublicationState { READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED }

/** The operations and recovery identifiers of a publication. */
public enum PublicationChange { PUBLISH, SCHEDULE_RETRY, RECORD_REJECTION, MARK_INTEGRATION_FAILED, RETRY, CLOSE }

/** Failure kinds reported by the publication adapter. */
public final class PublicationFailures {
    private PublicationFailures() { }

    /** The provider cannot take the publication now. */
    public record Unavailable() implements FailureKind { }

    /** The provider refused the content. */
    public record Rejected() implements FailureKind { }
}
```

Each public type goes in its own file. `ServiceUnavailableException`, `ProviderRejectedException` and
`InvalidResponseException` are the adapter's own unchecked exceptions. An action reports a kind by returning
`ActionOutcome.failed(kind, reason)`, or by throwing an exception whose most specific declared type is one of the
types of the rule that declares that kind, within the exclusions below. In the DSL, each `recover(kind, transition)`
call declares one rule. A third argument adds one throwable type to the rule, and a `List` adds several; the list is
copied when the rule is declared. A rule without types is selected only by a returned failure of its kind:

```java
GraphDefinition<Publication, PublicationState, PublicationChange> graph = StateGraph.define(key)
        .initial(PublicationState.READY)
        .terminal(PublicationState.PUBLISHED)
        .terminal(PublicationState.CLOSED)
        .allowsCycle(PublicationState.READY)
        .statePolicy(PublicationState.READY, "publish the article first")
        .statePolicy(PublicationState.RETRY_PENDING, "retry the publication explicitly")
        .statePolicy(PublicationState.REJECTED_BY_PROVIDER, "close the rejected publication")
        .statePolicy(PublicationState.INTEGRATION_FAILED, "close after manual review")
        .transition(PublicationChange.PUBLISH, PublicationState.READY, PublicationState.PUBLISHED)
            .action("publish", (TransitionContext<Publication, PublicationState, PublicationChange> ctx) ->
                    adapter.publish(ctx.entity()))
            .recover(PublicationFailures.Unavailable.class, PublicationChange.SCHEDULE_RETRY,
                    ServiceUnavailableException.class)
            .recover(PublicationFailures.Rejected.class, PublicationChange.RECORD_REJECTION,
                    ProviderRejectedException.class)
            .failsOn(InvalidResponseException.class)
            .onFailure(PublicationChange.MARK_INTEGRATION_FAILED)
            .endTransition()
        .transition(PublicationChange.SCHEDULE_RETRY, PublicationState.READY, PublicationState.RETRY_PENDING)
            .endTransition()
        .transition(PublicationChange.RECORD_REJECTION, PublicationState.READY,
                PublicationState.REJECTED_BY_PROVIDER)
            .endTransition()
        .transition(PublicationChange.MARK_INTEGRATION_FAILED, PublicationState.READY,
                PublicationState.INTEGRATION_FAILED)
            .endTransition()
        .transition(PublicationChange.RETRY, PublicationState.RETRY_PENDING, PublicationState.READY)
            .endTransition()
        .transition(PublicationChange.CLOSE, PublicationState.REJECTED_BY_PROVIDER, PublicationState.CLOSED)
            .endTransition()
        .transition(PublicationChange.CLOSE, PublicationState.INTEGRATION_FAILED, PublicationState.CLOSED)
            .endTransition()
        .build();
```

`adapter` stands for the application's client of the provider: its `publish` returns `ActionOutcome.ok()` or a
failure, or throws one of the three exceptions. With annotations, a `Transition` lists the same rules in
`recoveries`, one `Recovery` of the machine's vocabulary per rule. This operation belongs on a `Publications`
facade whose bindings host is `PublicationActions`, and its recovery edges are declared like `DECLINE` in the
quickstart, by type-level `Transition`s:

```java
@PublicationsGraph.Transition(id = PublicationChange.PUBLISH, from = PublicationState.READY,
    to = PublicationState.PUBLISHED,
    action = PublicationActionsRefs.Publish.class,
    recoveries = {
        @PublicationsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
            transition = PublicationChange.SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),
        @PublicationsGraph.Recovery(kind = PublicationFailures.Rejected.class,
            transition = PublicationChange.RECORD_REJECTION, failsOn = ProviderRejectedException.class)},
    failsOn = InvalidResponseException.class,
    onFailure = PublicationChange.MARK_INTEGRATION_FAILED)
Outcome<Publication, PublicationState, PublicationChange> publish(Publication publication);
```

A `Recovery` is written only as a value of `recoveries`. Several values are several rules, not several fallbacks,
so they are not `SG607`. Whichever way it is declared, the edge routes admitted failures according to its rules
and fallback. In this table, `Unavailable` and `Rejected` are the two members of `PublicationFailures`:

| `PUBLISH`'s action | Recovery edge | `movedTo()` | `failureKind()` | `cause()` |
| --- | --- | --- | --- | --- |
| returns `failed(Unavailable.class, …)` | `SCHEDULE_RETRY` | `RETRY_PENDING` | `Unavailable` | empty |
| throws `ServiceUnavailableException` | `SCHEDULE_RETRY` | `RETRY_PENDING` | `Unavailable` | the exception |
| returns `failed(Rejected.class, …)` | `RECORD_REJECTION` | `REJECTED_BY_PROVIDER` | `Rejected` | empty |
| throws `ProviderRejectedException` | `RECORD_REJECTION` | `REJECTED_BY_PROVIDER` | `Rejected` | the exception |
| returns `failed("invalid response")` | `MARK_INTEGRATION_FAILED` | `INTEGRATION_FAILED` | empty | empty |
| throws `InvalidResponseException` | `MARK_INTEGRATION_FAILED` | `INTEGRATION_FAILED` | empty | the exception |
| throws any other exception | none: the call aborts | no outcome; the entity stays `READY` | — | — |

A returned failure is routed by its kind alone:

| The action returns | A rule declares that kind | `onFailure` declared | Result |
| --- | --- | --- | --- |
| `failed(reason)` | — | yes | the fallback, with no kind |
| `failed(reason)` | — | no | `UnhandledFailureException` |
| `failed(kind, reason)` | yes | either way | that rule's recovery, with its kind |
| `failed(kind, reason)` | no | yes | the fallback; `failureKind()` still reports the kind |
| `failed(kind, reason)` | no | no | `UnhandledFailureException`, which does not carry the kind |

The kind changes nothing about admission: returning a failure declares a domain failure, with a kind or without
one. Kinds have no hierarchy, the reason selects nothing, and a returned failure carries no cause.

An exception is routed by the most specific declared type that accepts it. The types of every rule and the
types of the edge's own `failsOn` compete together. A type in a rule leads to that rule's recovery, with its kind;
a type in `failsOn` leads to the fallback, with no kind. Neither the order of the declarations nor the place a type
is declared changes the winner. On an edge with an action that declares

```java
.failsOn(SocketTimeoutException.class)
.onFailure(PublicationChange.MARK_INTEGRATION_FAILED)
.recover(PublicationFailures.Unavailable.class, PublicationChange.SCHEDULE_RETRY, IOException.class)
```

a `SocketTimeoutException` goes to `MARK_INTEGRATION_FAILED` with no kind, because its own type is declared and is
more specific than `IOException`. Any other `IOException` goes to `SCHEDULE_RETRY` with the kind `Unavailable`.
Swap the two types, `failsOn(IOException.class)` and the rule on `SocketTimeoutException`, and the timeout goes to
the rule while every other `IOException` goes to the fallback.

- One type declared in two rules, or in a rule and in `failsOn`, is `SG304`, so the winner is always one type. A
  type repeated inside `failsOn` alone is accepted, since every copy leads to the fallback.
- `onFailure` never admits a throwable by itself: a throwable that no declared type accepts aborts the call with it as
  cause even when the edge has a fallback, except an `Error` thrown as itself, which propagates, and a failure of the
  call itself, which the engine throws as itself or, when the transaction is poisoned, as the failure that poisoned
  the transaction. If the most specific matching type belongs to the edge's own `failsOn` and the edge has no
  `onFailure`, the call ends in `UnhandledFailureException`, unless a failed cascade poisoned the transaction, in which
  case the engine throws the failure that poisoned the transaction.
- The reason is composed from the exception's simple name and its message, or from its binary name for an
  anonymous class. A message that cannot be read, because `getMessage()` throws anything but an `Error`, reads
  `<getMessage threw <exception class>>`, and the route is taken all the same, with the thread interrupted again when
  what `getMessage()` threw is an `InterruptedException`. `cause()` is the very exception the action threw, once
  unwrapped.
- Wherever the engine contains an `InterruptedException` that application code throws on the thread the engine called it
  on, it interrupts that thread again, since the blocking call that threw it cleared the interrupt: one that an action
  or a guard throws, whatever the engine then does with it, and one that a hook, a state getter or setter, an identity
  function, the trail's clock, a sub-machine's `childOf` or `parentOf`, a watch's `peersOf`, `ownerOf` or condition, an
  observer, the logger's backend, a value's `toString()` or a call submitted through `supplyAsync` throws. One that
  completes a stage leaves the thread as it is.
- `explanation()` holds the reason of a returned failure and stays empty when an exception was routed, whichever
  route took it.

The engine keeps the boundary between domain failures and technical ones:

- Only what the action throws, or completes its stage with, is classified. A `CompletionException` or an
  `ExecutionException` that carries a cause is unwrapped first; a subclass of either is classified as itself.
- `Error`, `CancellationException` and `StateMachineException`, with their subtypes, are excluded: the runtime never
  matches them, so no declared type accepts them, whatever an edge declares, and it excludes them before it reads any
  declaration, so a rule on `IllegalStateException` never receives a `CancellationException`. Exclusion is what the
  runtime never matches; `SG302` is what you may not declare, in a rule as in `failsOn`: `Throwable`, `Exception`,
  `RuntimeException`, an `Error`, a library exception, a `CancellationException` or one of its subtypes, and exactly
  `CompletionException` or `ExecutionException`. An `Error` the action throws propagates as itself; one that completes
  the stage, or arrives wrapped, aborts the call with it as cause.
- A failure of the call itself, such as the abort a failed cascade throws into the action, is never routed: the engine
  throws it as itself or, when the transaction is poisoned, the failure that poisoned the transaction. Once a failed
  cascade poisoned the unit of work, an exception that a declared type accepts, from an action that does not run as a
  recovery action, takes no route either: the engine throws the failure that poisoned the transaction, before any route
  is chosen.
- A cancelled stage, and the timeout, interruption or refusal of the engine's own wait, abort the call and never
  reach a rule. A timeout that the adapter's own client throws, such as a `SocketTimeoutException`, is an exception
  like any other and can be declared.
- The engine throws a few JDK exceptions from an action's context and its flow: `NullPointerException` for a null
  argument, `IllegalArgumentException` for a primitive class token in `input(Class)` or `flow().entity(Class)` or an
  identifier of another catalogue, `ClassCastException` when `input(Class)` reads an input of another type, when
  `flow().put` receives a value that is not an instance of its key's type or when `flow().entity(Class)` names a
  class the root entity is not an instance of, and `WrongThreadException` when the context or its flow is used from
  another thread than the one running the action. They are not library exceptions: if the action lets one through and a
  declared type accepts it, a rule or `failsOn` routes it like the adapter's own exceptions. None of them is an
  `IllegalStateException`. The context does throw one for a cascade attempted once the commit has begun or while a
  step is rolled back, but only a retained context, called from a getter, a setter or an identity's `equals` or
  `hashCode`, can attempt that, never your running action. Declare the narrowest types the adapter throws.
- Checked exceptions are classified like the others, however they arrive: thrown by a synchronous action without
  being declared, declared by an annotated action method and rethrown unchanged by the generated companion, or
  completing a stage, inside an exact `CompletionException` too.
- Synchronous and asynchronous actions use the same selection: a returned failure, an exception thrown before the
  stage is handed back and an exceptional completion select the same route, kind and cause.

A recovery is an ordinary edge, resolved by its identifier from the origin of the edge that failed, as the
fallback is. The recovery identifier of every rule, like the fallback's, needs an edge from that origin, from each
of them when a `Transition` declares several, and may not be the failing edge's own key: `SG301` otherwise. Two
rules may share a recovery, and a recovery may lead to the state the failing edge was heading for. Before the
recovery runs, the engine returns to the savepoint taken before the failing edge, which discards that edge's pending
states and cascades, its destination included, and what its action and those cascades wrote to
[the call's flow](#the-calls-flow); the journal keeps the actions that started. Entered as a recovery, the edge, a
move or an action:

- runs none of its guards;
- passes the same input to its action;
- consults neither its own rules nor its own `onFailure`, so recoveries never chain;
- may cascade, within the usual limits;
- waits under its own effective timeout, inside the budget of the root call, and runs its own effective hook after
  the commit.

Requested by an ordinary call, the same identifier runs its guards and applies its own rules. If the recovery
fails, because it returns a failure, throws, fails its wait or runs a cascade that fails, the call aborts as that
failure always aborts: no other rule is tried and the fallback is not used. The journal still lists the actions
that ran, but not the kind or the cause of the first failure. When the recovery action throws, or completes its stage
with, an exception that aborts the call, the abort also holds the exception that selected the route, if one did, as
suppressed, unless the recovery action threw that very exception. So does the engine's own abort of the recovery step,
for a null outcome or stage or a wait that timed out, was interrupted or was refused, unless a failed cascade had
poisoned the call, which then ends in that poison as it is. A definition assembled without the DSL whose rule names an
identifier with no edge from the origin aborts the call when that rule is selected, as such an `onFailure` does.

`Outcome.Failed` reports the kind as `failureKind()`, empty when the failure has none, beside
`failureTransition()`, the identifier of the recovery edge that ran. `status()` is `FAILED` and `code()` is
`FAILURE_ROUTED` whichever route was taken, and the `Outcome.Failed` that a cascade returns carries its kind to the
calling action. A failure that no rule and no fallback takes still ends the call in `UnhandledFailureException`,
which does not carry the kind:

```java
Outcome<Publication, PublicationState, PublicationChange> outcome =
        machine.execute(publication, PublicationChange.PUBLISH);
Optional<Class<? extends FailureKind>> kind = outcome.asFailed().flatMap(Outcome.Failed::failureKind);
boolean retryLater = kind.equals(Optional.of(PublicationFailures.Unavailable.class));
```

`definition().findTransition(from, id)` returns the edge's `TransitionSpec`: `recoveryRules()` lists its rules in
declaration order, and `failsOn()` still lists only the edge's own types. The order of the rules selects nothing.

The DSL refuses at once a null argument, a null type in the list, an identifier of another catalogue and a call
on a closed stage. It reports the rest when `build()` runs, naming the edge, without building any invalid rule:

- `SG303` for a class that is not a failure kind;
- `SG304` for a kind used by two rules of the edge, or a throwable type declared twice across its rules and
  `failsOn`;
- `SG302` for a type that `failsOn` refuses too;
- `SG507` for rules on an edge without an action;
- `SG301` for a recovery identifier that is the edge's own key or has no edge from one of its origins.

The annotation processor reports the same codes when it compiles the facade, `SG302`, `SG303` and `SG304` on the
annotation value at fault. Built by hand, a `RecoveryRule` refuses an invalid kind and a forbidden or repeated type,
and a `TransitionSpec` refuses rules without an action, a kind that two rules use and a type declared twice across
its rules and `failsOn`, all with `IllegalArgumentException`.

Recovery rules choose a route and nothing more. Nothing retries automatically or waits for a backoff:
`SCHEDULE_RETRY` only records that a retry is due, and the application decides when to request `RETRY`. Nothing
compensates either: the savepoint discards the engine's pending states and the writes to the call's flow, not the
changes the action made to the entity, to an object it stored in the flow, to the application's transaction or to
external systems. To assert kinds and rules in a test, see
[failure kinds and recovery rules](testing.md#failure-kinds-and-recovery-rules) in the testing guide.

## Handle transition outcomes

![An operation resolves one edge by identity. With no edge the node's policy answers; otherwise guards run, then the action, and the outcome is applied, the declared failure route, or a technical exception. A sub-machine whose parent does not hold it is rejected before the edge is resolved, and an exit reserved to the sub-machine bound to the origin once it is resolved. A step that ends a held sub-machine also commits the parent's exit, unless the parent is in flight or the step moved it.](images/operation-decisions.svg)

`Outcome<E, S, T>` is a sealed interface with four variants. Every outcome classifies itself, so a caller that
only needs to know what happened and why reads it without narrowing:

| Variant | `status()` | `code()` | `explanation()` | State written? |
| --- | --- | --- | --- | --- |
| `Applied` | `APPLIED` | `APPLIED` | empty | Yes, the resolved edge committed |
| `Rejected` | `REJECTED` | `NO_TRANSITION`, `GUARD_REJECTED`, `DETACHED` or `RESERVED_EXIT` | the policy's reason, the guard's, or the library's fixed text for the last two | No |
| `Failed` | `FAILED` | `FAILURE_ROUTED` | the reason the action returned; empty when `failsOn` or a recovery rule routed a throwable | Yes, the failure route committed |
| `Ignored` | `IGNORED` | `NO_WATCH`, or `NO_RULE_MATCHED` | empty | No |

A cascade returns `Applied` or `Failed` to the action that called `cascade(...)`; a rejection inside it aborts the
root instead. Both are provisional there: their hops are written only if the root commits.

Every outcome also has `entity()`, `from()`, `recordedState()`, `requestedTransition()`, `records()`,
`reactions()`, `starts()` and `flow()`. `recordedState()` is `to` for `Applied`, `movedTo` for `Failed` and `from` for
the other two. `requestedTransition()` is empty only for `Ignored`, which selected no edge at all. [Records, journals
and reactions](#records-journals-and-reactions) describes what `records()`, `reactions()`, `starts()` and `flow()`
return.

Read the classification with its limits in mind:

- `recordedState()` is what the call recorded, not a read of the entity. An after-commit hook, a watch reaction
  or a later call can move the entity again; read the entity when you need its live state.
- `FAILED` does not mean that nothing happened: the failure route committed and the entity is at `movedTo`.
- `explanation()` holds the reasons the graph declares: a policy's reason, a guard's, or the reason an action
  returned with `ActionOutcome.failed(...)`. The only texts the library writes there are the fixed reason of a
  terminal state that declares no policy, such as `terminal state DONE accepts no transitions`, and the fixed reasons
  of the two refusals of a sub-machine: `DETACHED`, such as `no parent holds it in DEPOSIT`, and `RESERVED_EXIT`, such
  as `DEPOSIT_PAID leaves DEPOSIT only when its sub-machine ends`. When `failsOn` or a
  recovery rule routes an exception, the failure's reason is composed from that exception and may carry internal
  details; it stays in `Failed.reason()` and `Failed.cause()`.
- A commit here is GraphMaquina writing states through the setters. A JPA or other transaction around the call
  commits later and can still fail.
- The constant names of `OutcomeStatus` and `OutcomeCode` are stable identifiers, safe to persist or send.

This method can be added to the quickstart's `App`:

```java
static String describe(Outcome<Ticket, TicketState, TicketTransition> outcome) {
    return outcome.code() + " at " + outcome.recordedState()
            + outcome.explanation().map((String text) -> ": " + text).orElse("");
}
```

### Map an outcome to a response

A web layer copies the classification into a type it owns, so its contract does not change when the library
does. The HTTP mapping below is one application's policy, not the library's; another application may just as well
answer `409` for every rejection and every handled failure:

```java
record TransitionResponse(String id, OutcomeStatus status, OutcomeCode code, TicketState from,
                          TicketState state, TicketTransition transition, String explanation) {

    static TransitionResponse of(String id, Outcome<Ticket, TicketState, TicketTransition> outcome) {
        return new TransitionResponse(id, outcome.status(), outcome.code(), outcome.from(),
                outcome.recordedState(), outcome.requestedTransition().orElse(null),
                outcome.explanation().orElse(null));
    }
}

static int httpStatus(OutcomeCode code) {
    return switch (code) {
        case APPLIED, FAILURE_ROUTED, NO_WATCH, NO_RULE_MATCHED -> 200;
        case NO_TRANSITION, DETACHED, RESERVED_EXIT -> 409;
        case GUARD_REJECTED -> 422;
    };
}
```

The `switch` has no `default`: it is exhaustive, so a code added by a future release fails the build instead of
falling through. Do not serialize the `Outcome` itself: it holds the entity, a `Throwable`, reactions with their own
entities, and the call's flow, whose values are whatever the steps and hooks put. A call failure cannot be serialized
at all: every `CallFailedException`, whatever its subtype, holds its `MachineCall`, which is not `Serializable`, so
writing one to an `ObjectOutputStream` throws `NotSerializableException`.

### Read what only one variant carries

The sealed variants remain available when a caller needs what only one of them carries: the rejecting guard,
the recovery identifier, the requested destination of a failure or its cause. `asApplied()`, `asRejected()`,
`asFailed()` and `asIgnored()` return the outcome as that variant, with the outcome's type arguments, or empty:

```java
Optional<String> guard = outcome.asRejected().flatMap(Outcome.Rejected::guard);
Optional<TicketTransition> recovery = outcome.asFailed().map(Outcome.Failed::failureTransition);
```

A `switch` over the variants reads all of them at once:

```java
static String detail(Outcome<Ticket, TicketState, TicketTransition> outcome) {
    return switch (outcome) {
        case Outcome.Applied<Ticket, TicketState, TicketTransition> applied ->
                applied.transition() + " moved to " + applied.to();
        case Outcome.Rejected<Ticket, TicketState, TicketTransition> rejected ->
                "Refused " + rejected.transition() + " by " + rejected.guard().orElse("the state's policy");
        case Outcome.Failed<Ticket, TicketState, TicketTransition> failed ->
                failed.failureTransition() + " moved to " + failed.movedTo() + ": " + failed.reason();
        case Outcome.Ignored<Ticket, TicketState, TicketTransition> ignored ->
                "Unchanged at " + ignored.recordedState() + " (" + ignored.reason() + ")";
    };
}
```

Technical failures throw instead of becoming a fifth outcome. An abort (`TransitionAbortedException`), a commit
failure (`CommitFailedException`) and a failing hook (`AfterCommitException`) share the supertype
`CallFailedException`. It says as values which call failed (`call()`), where it was (`phase()`), how far it got
in writing states (`statesWritten()`) and which actions ran (`journal()`), and it hands out the call's flow
(`flow()`). A hook fails after state was already written. [Handle a failed call](#handle-a-failed-call) shows how to
read them.

### Records, journals and reactions

- `TransitionRecord` describes a hop the call took: machine key, entity, source, transition identifier,
  destination, kind, action label and cascade depth. Two actions that share a name and endpoints stay
  distinguishable because the identifier is part of the record. The records of an outcome and of an
  `AfterCommitException` are committed hops. In the trail of any other failure, a record is a hop the call took,
  and that failure's `statesWritten()` says whether none or some of their states were written.
- `records()` and `reactions()` answer on every outcome. A root `Applied` or `Failed` returns the
  transaction's records and the reactions of its commit; `Rejected`, `Ignored` and a nested `cascade` result
  return empty lists, the last because it is provisional and has no independent root commit or reaction list.
- `starts()` answers on every outcome too, and is empty in the same cases. When an edge enters a state that holds a
  sub-machine, the child moves to the initial state of its graph in the same unit of work, and a root `Applied` or
  `Failed` returns a `SubmachineStart` for each such start, in log order: the child's machine and entity, the state
  it had and the one it started in, the parent's machine, entity and bound state, and the depth of the step. A start
  is no hop, so it is not in `records()` and runs no hook.
- `flow()` answers on every outcome as well. A root `Applied` or `Failed` hands out the call's flow, frozen once its
  hooks and reactions ran; `Ignored`, a nested `cascade` result, and a `Rejected` or any other root outcome of a call
  that records no trail and whose steps and hooks wrote nothing to its flow return `FlowView.empty()`.
  [Read the flow after the call](#read-the-flow-after-the-call) says what it holds, and
  [record the call's trail](#record-the-calls-trail) how its trail lists the hops and starts of `records()` and
  `starts()`.
- A failed call's journal uses `ActionRecord` and `ActionStatus` (`OK`, `FAILED`, `THREW`; never `RUNNING` once the
  call failed) to report actions that actually ran. A journal is execution evidence, not an undo log for external
  side effects.
- A watch produces `Reaction.Done`, carrying its own outcome, with the reaction's own flow, or `Reaction.Aborted`,
  carrying its exception. Inspect
  reactions even when the root operation succeeded. `Reaction.Aborted` can also report a hook failure after the reaction
  itself committed; it is not proof that the reaction left state unchanged. A reevaluation that failed carries its
  `CallFailedException`, whose `statesWritten()` says whether the reaction wrote anything. An owner lookup fails before
  any reevaluation, so the reaction carries what it threw: a `RuntimeException` as it is, and a checked exception or any
  other throwable that is neither a `RuntimeException` nor an `Error`, wrapped in `UndeclaredThrowableException`, with
  the thread interrupted again when what it threw is an `InterruptedException`. An `Error` is not caught: it propagates
  to the caller whose commit triggered the reaction.

### Handle a failed call

A call that does not return an outcome throws an `Error` or one of three `CallFailedException`s, always one of that
very call. A request refused before the call starts is not a call failure: a null argument throws
`NullPointerException`, an identifier of another catalogue `IllegalArgumentException`, and a call made where
no call may start `ReentrantTransitionException`. Read what happened from its values, not from its message:

| Type | `phase()` | `statesWritten()` |
| --- | --- | --- |
| `TransitionAbortedException` and its subclasses | `RESOLVE`, `GUARD` or `ACTION` | `NONE` |
| `CommitFailedException` | `COMMIT` | `NONE`, or `PARTIAL` when a setter threw |
| `AfterCommitException` | `HOOK` | `ALL` |

`call()` is the call that failed, the same `MachineCall` an observer received, and `journal()` lists the actions that
ran. Any other exception involved is the cause, a failure of another call included, which keeps the flow of its own
call.

`flow()` is the call's flow, frozen when the call failed and attached before the observers hear of the failure. It
reads like an outcome's, as [read the flow after the call](#read-the-flow-after-the-call) shows, and holds what the
call wrote up to the failure:

- An abort undoes no write itself, so the failure keeps what the step that aborted and the steps before it wrote,
  except what a rollback removed earlier. A step whose failure its edge expects, a failure its action returned or a
  throwable of a type the edge declares, is rolled back whether a route takes the failure or not: an
  `UnhandledFailureException` therefore lacks the writes of its step and of the steps that step cascaded to. Each
  `COLLECT` element whose abort the cascade collects is rolled back too, so a `CascadeException` lacks their writes; an
  identity whose `equals` or `hashCode` throws stops that rollback, which leaves the writes and hops it had not undone.
  Once a failed cascade poisoned the call, a step whose action still returns or throws such a failure is not rolled back
  for it: it ends in the failure that poisoned the call and keeps its writes and its hop, unless a `COLLECT` collects
  that failure, which rolls back the whole element the step ran in.
- A `CommitFailedException` holds everything the steps wrote.
- An `AfterCommitException` also holds what the hooks wrote, the failing hook's writes before it threw included.
- When the call records its trail, its flow also lists the hops and starts the call took up to the failure, except
  those a rollback removed and the last one when the clock could not read its instant, which fails the call,
  followed by a `FlowEntry.Failed` entry with the failure, unless the trail's clock threw when
  the call read that entry's instant, as [record the call's trail](#record-the-calls-trail) shows.
- A `reevaluate` that fails before it runs an edge, while it reads the state, evaluates its watch or exceeds the
  limit of a watch chain, hands out `FlowView.empty()`, unless its machine records its trail: its flow then holds the
  entity, no value, and the `Failed` entry alone, or no entry when the clock threw while it read the entry's instant.
- Only the failure the call throws carries the call's flow: any other failure of the call holds `FlowView.empty()`,
  such as an element of the `errors()` of a `CascadeException` or a `PoisonedUnitOfWorkException` a context threw.
  A call that records no trail and whose steps and hooks wrote
  nothing to its flow hands out `FlowView.empty()` too.
- An `Error` carries no flow: it propagates as itself.

With
`import io.github.graphmaquina.error.CallFailedException;`, this method can be added to the quickstart's `App`:

```java
static String describe(CallFailedException failure) {
    String written = switch (failure.statesWritten()) {
        case NONE -> "no state was written";
        case PARTIAL -> "some states were written";
        case ALL -> "every state was written";
    };
    String actions = failure.journal().isEmpty() ? "no action ran" : failure.journal().size() + " action(s) ran";
    return failure.call() + " failed in " + failure.phase() + ": " + written + ", " + actions;
}
```

`statesWritten()` describes the setters only: a JPA or other transaction around the call commits or rolls back later,
on its own. Neither the phase nor `statesWritten()` decides a retry by itself. `NONE` with an action in the journal
that called a payment service means the payment may have happened although no state moved. Retry only what your
actions make idempotent, and reconcile the rest.

## Asynchronous work and timeouts

GraphMaquina supports asynchronous actions and guards while keeping each transition's execution and commit
ordered. There are two independent decisions: whether a binding returns a stage, and whether the caller
submits the whole operation through `machine.async(...)`.

Read the subsections in this order:

- [Choose the calling style](#choose-the-calling-style).
- [Build a runnable async ticket](#runnable-async-ticket-example).
- [Read the future and domain result](#read-the-future-and-domain-result).
- [Configure deadlines](#deadline-rules).
- [Handle timeout and cancellation](#timeout-cancellation-and-late-completion).
- [Use contexts safely](#contexts-and-completion-callbacks).
- [Manage executors and machine lifetime](#executors-and-machine-lifetime).
- [Test async behavior](#test-async-behavior).

### Choose the calling style

| Action/guard implementation | How you invoke the machine | What the caller experiences |
| --- | --- | --- |
| Returns `ActionOutcome` / `GuardResult` | `tickets.complete(ticket)` | Executes inline and returns an `Outcome` |
| Returns `CompletionStage<ActionOutcome>` / `CompletionStage<GuardResult>` | `tickets.complete(ticket)` | Invokes the binding inline, waits for its stage, then returns an `Outcome` |
| Either kind of binding | `tickets.async(() -> tickets.complete(ticket))` | Submits the whole operation to the configured executor and returns `CompletableFuture<Outcome<...>>` |

With the default executor, the last form runs the operation on a virtual thread, allowing the calling thread
to continue. A custom executor determines its own scheduling; a direct executor can execute inline. The whole
operation runs on the executor of the machine you called, the exit of a sub-machine it ends included, and the
binding of a sub-machine is checked when the operation starts running, not when it is submitted.

![Direct invocation waits on the caller thread; machine.async uses the default virtual-thread executor to run the operation while the caller receives a future. Both paths wait for the action stage before committing.](images/async-execution.svg)

Returning a stage does not change the generated operation's return type: the facade still returns
`Outcome<E, S, T>`. The outer `CompletableFuture` comes from `machine.async(...)`. Calling `join()` immediately
on that future blocks the caller again; at an asynchronous application boundary, compose and return the
future instead of joining it on an event-loop thread.

Guards execute in order. Each async guard is awaited before the next guard or action runs. A guard rejection
stops the edge. The action method itself runs on the operation thread up to the point where it returns its
stage. Any blocking I/O performed before that return still blocks the operation thread. Use a service API
that returns promptly, or an application-managed executor for genuinely blocking service code.

### Runnable async ticket example

This example adds an asynchronous eligibility check and approval service to the ticket domain. Reuse
`Ticket.java`, `TicketState.java` and `TicketTransition.java` from the
[quickstart](#quickstart-a-complete-ticket-machine), with the same Gradle or Maven configuration and
dependencies. Add the following five files under `src/main/java/com/example/tickets/`. They can coexist with
the synchronous quickstart: a second machine over the same three types is its own graph.

The service contract returns stages; the example implementation controls their completion explicitly so
there is no external server, `Thread.sleep`, or scheduling guesswork.

#### TicketReviewService.java

```java
package com.example.tickets;

import java.util.concurrent.CompletionStage;

public interface TicketReviewService {
    CompletionStage<Boolean> canStart(String ticketId);
    CompletionStage<Boolean> approved(String ticketId);
}
```

#### AsyncTicketActions.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class AsyncTicketActions {
    private final TicketReviewService reviews;

    public AsyncTicketActions(TicketReviewService reviews) {
        this.reviews = Objects.requireNonNull(reviews);
    }

    @GraphGuard
    public CompletionStage<GuardResult> canStart(GuardContext<Ticket, TicketState, TicketTransition> ctx) {
        String ticketId = ctx.entity().getId();
        return reviews.canStart(ticketId).thenApply(allowed -> allowed
                ? GuardResult.pass()
                : GuardResult.reject("ticket is not eligible"));
    }

    @GraphAction
    public CompletionStage<ActionOutcome> approve(TransitionContext<Ticket, TicketState, TicketTransition> ctx) {
        String ticketId = ctx.entity().getId();
        return reviews.approved(ticketId).thenApply(approved -> approved
                ? ActionOutcome.ok()
                : ActionOutcome.failed("approval was declined"));
    }
}
```

Both methods capture the immutable identifier before calling the service. Their callbacks only translate a
service value; they do not use the transaction context or mutate the entity. Approval comes from the service
in this example, rather than the quickstart entity's `approved` field.

#### AsyncTickets.java

```java
package com.example.tickets;

import static com.example.tickets.TicketState.DONE;
import static com.example.tickets.TicketState.FAILED;
import static com.example.tickets.TicketState.OPEN;
import static com.example.tickets.TicketState.REVIEW;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.Timeout;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import java.io.IOException;

@GenerateGraphApi
@AsyncTicketsGraph.Definition(initial = OPEN, terminals = {DONE, FAILED},
        state = TicketRefs.State.class, id = TicketRefs.Id.class)
@AsyncTicketsGraph.StatePolicy(state = OPEN, otherwise = "submit the ticket first")
@AsyncTicketsGraph.StatePolicy(state = REVIEW, otherwise = "approve or decline the ticket")
@AsyncTicketsGraph.Transition(id = TicketTransition.DECLINE, from = REVIEW, to = FAILED)
public interface AsyncTickets extends Machine<Ticket, TicketState, TicketTransition> {
    @AsyncTicketsGraph.Transition(id = TicketTransition.SUBMIT, from = OPEN, to = REVIEW,
            guards = AsyncTicketActionsRefs.CanStart.class)
    Outcome<Ticket, TicketState, TicketTransition> submit(Ticket ticket);

    @AsyncTicketsGraph.Transition(id = TicketTransition.APPROVE, from = REVIEW, to = DONE,
            action = AsyncTicketActionsRefs.Approve.class,
            onFailure = TicketTransition.DECLINE,
            failsOn = IOException.class,
            timeout = @Timeout(seconds = 2))
    Outcome<Ticket, TicketState, TicketTransition> approve(Ticket ticket);
}
```

`canStart` uses the machine's root wait deadline. The `approve` action also has a two-second wait limit. A
service result of `false` becomes a handled failure; a stage completed exceptionally with `IOException` also
takes the `DECLINE` edge, resolved from `REVIEW`. A guard exception or an action wait timeout is a technical
abort, even though the action declares `onFailure`.

#### AsyncTicketMachines.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

@StateGraphs
public interface AsyncTicketMachines extends Machines {
    AsyncTickets tickets();
}
```

#### AsyncApp.java

```java
package com.example.tickets;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AsyncApp {
    private AsyncApp() { }

    public static void main(String[] args) {
        CompletableFuture<Boolean> approval = new CompletableFuture<>();
        TicketReviewService reviews = new TicketReviewService() {
            @Override
            public CompletionStage<Boolean> canStart(String ticketId) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletionStage<Boolean> approved(String ticketId) {
                return approval;
            }
        };

        try (AsyncTicketMachines machines = AsyncTicketMachinesRuntime.builder(
                Duration.ofSeconds(5), new AsyncTicketActions(reviews)).build()) {
            AsyncTickets tickets = machines.tickets();
            Ticket ticket = new Ticket("T-ASYNC", "Review the documentation", true);

            Outcome<Ticket, TicketState, TicketTransition> submitted =
                    tickets.async(() -> tickets.submit(ticket)).join();
            if (submitted.status() != OutcomeStatus.APPLIED) {
                throw new IllegalStateException("submit was not applied: " + submitted);
            }

            CompletableFuture<Outcome<Ticket, TicketState, TicketTransition>> pending =
                    tickets.async(() -> tickets.approve(ticket));
            System.out.println("Caller can do other work while approval is pending.");

            // In a real service, its client completes this stage when the response arrives.
            approval.complete(true);

            Outcome<Ticket, TicketState, TicketTransition> approved = pending.join();
            if (approved.status() != OutcomeStatus.APPLIED
                    || ticket.getState() != TicketState.DONE) {
                throw new IllegalStateException("approval was not applied: " + approved);
            }
            System.out.println("Async ticket -> " + ticket.getState());
        }
    }
}
```

This command-line demonstration deliberately joins before exiting so the machine set remains alive for all
submitted work. In Gradle, change the existing `application.mainClass` to `"com.example.tickets.AsyncApp"`,
then run `./gradlew clean run`. In Maven:

```bash
mvn clean compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" com.example.tickets.AsyncApp
```

Expected application output:

```text
Caller can do other work while approval is pending.
Async ticket -> DONE
```

To explore another branch, complete `approval` with `false` instead of `true`: the operation then returns
`Outcome.Failed` and the ticket becomes `FAILED`, so update the demonstration's success assertion accordingly.
Use `approval.completeExceptionally(new java.io.IOException("service unavailable"))` to exercise `failsOn`.
An unexpected exception, such as `IllegalStateException`, completes the operation future exceptionally.

### Read the future and domain result

There are two layers of result. A future that completes normally does not imply `Outcome.Applied`:
`Rejected`, `Ignored` and a handled `Failed` are normal values too.

![The operation future branches into normal completion carrying Applied, Failed, Rejected or Ignored, and exceptional completion carrying a technical or post-commit failure. Watch reactions must also be inspected.](images/async-future-results.svg)

With the `tickets` and `ticket` from the example, this fragment reacts to completion without joining:

```java
CompletableFuture<Outcome<Ticket, TicketState, TicketTransition>> pending =
        tickets.async(() -> tickets.approve(ticket));

CompletableFuture<Void> handled = pending.thenAccept((Outcome<Ticket, TicketState, TicketTransition> outcome) -> {
    switch (outcome.status()) {
        case APPLIED -> System.out.println("Committed: " + outcome.requestedTransition().orElseThrow()
                + " -> " + outcome.recordedState());
        case FAILED -> System.out.println("Handled decline at " + outcome.recordedState()
                + outcome.explanation().map((String text) -> ": " + text).orElse(""));
        case REJECTED -> System.out.println("Not allowed: " + outcome.explanation().orElseThrow());
        case IGNORED -> System.out.println("Unchanged: " + outcome.recordedState());
    }
});

handled.whenComplete((Void unused, Throwable error) -> {
    if (error != null) {
        System.err.println("Operation or callback failed: " + error);
    }
});
```

Keep or return the relevant future to the caller. `join()` wraps an exceptional completion in
`CompletionException`; `get()` uses `ExecutionException` and can be interrupted. If the outer future itself
is cancelled, either method can throw `CancellationException` directly. When the call ran and failed, the cause is
that call's `CallFailedException`, or an `Error`. The future fails even when that cause cannot be printed, because
its `getMessage()` or `toString()` throws: the `CompletionException`'s message is then
`<toString threw <exception class>>`, naming what printing threw, and `join()` throws it. `get()` throws what a
`CompletableFuture`'s `get()` throws for such a cause on the JDK it runs on: on JDK 21, what printing the cause
throws, as it builds its `ExecutionException` from the cause's string; on JDK 25, an `ExecutionException` caused by
it, whose message is empty and which carries what printing threw as a suppressed exception. Read the
`CallFailedException`'s `statesWritten()` and `journal()`, as [Handle a failed call](#handle-a-failed-call) shows,
before deciding whether to retry; an exceptional future is not proof that nothing happened. A request refused before
it started ran nothing: one an executor refused
(`RejectedExecutionException`), one with a null argument (`NullPointerException`, whose message names that parameter),
one for an identifier of another catalogue (`IllegalArgumentException`), and one made where no call may start
(`ReentrantTransitionException`, possible when the call runs on the thread of the callback that submitted it, through
an executor that runs tasks inline or a wait that runs a `ForkJoinPool`'s queued tasks). A null argument fails the
future only when the operation you submit receives it: `async(null)` itself, and a null argument of a low-level
`StateMachine`'s `async().execute(...)` or `async().reevaluate(...)`, throw their `NullPointerException` on the calling
thread before anything is submitted. A callback you attach can also fail independently of the machine operation.

Avoid a blanket `exceptionally(error -> ActionOutcome.failed(...))` inside an action unless every covered
exception truly means a business decline. It would also turn unexpected service/programming errors into a
normal failure route. Return `ActionOutcome.failed(reason)`, or `ActionOutcome.failed(kind, reason)`, for a known
business failure, and declare specific throwable types, in a recovery rule or in `failsOn`, when the graph is
intended to handle those action exceptions.

### Deadline rules

A generated set with declared async actions or guards requires a positive default `Duration` as the first
builder argument. The runnable example uses five seconds. An optional per-machine override changes the
budget installed on that asynchronous machine:

```java
AsyncTicketMachines machines = AsyncTicketMachinesRuntime.builder(
        Duration.ofSeconds(10), new AsyncTicketActions(reviews))
        .asyncTimeout(AsyncTickets.class, Duration.ofSeconds(8))
        .build();
```

Close `machines` when its work is finished. The override is accepted only for an asynchronous machine in the
set; any other class throws `IllegalArgumentException`. A synchronous-only set such as `TicketMachines` has no
timeout builder argument and no `asyncTimeout(Machine.class, Duration)` method, even when you use its `async(...)`
method to submit a synchronous operation.

Any positive duration is accepted. One of `Duration.ofNanos(Long.MAX_VALUE)` or more means no limit, for a
machine's budget and for an action's `@Timeout` alike.

| Setting | When its clock starts | What it bounds |
| --- | --- | --- |
| Set default or per-machine `asyncTimeout` | When a root operation begins execution, after executor queueing | Pending async waits in that root, its nested cascades and the sub-machine exits it runs |
| Action `@Timeout` | After the action method returns its stage | That action's pending wait, capped by the root's remaining deadline |
| Async guard | Uses the existing root deadline | That guard's pending wait; an action's `@Timeout` does not cover its guards |
| Service/client timeout | Defined by your service client | The service request according to that client's cancellation/timeout contract |

![An eight-second root budget starts with root execution. If an action returns its stage at second four, a five-second action timeout still allows only four seconds of waiting; a two-second action timeout allows two seconds.](images/async-deadlines.svg)

The effective action wait deadline is the earlier of the root deadline and the action-specific deadline.
If guards and other synchronous work have already consumed four seconds of an eight-second root budget, an
async action with `@Timeout(seconds = 5)` cannot get five additional seconds. The diagram illustrates this
with a stage returned at second four.

These settings are wait budgets, not a hard end-to-end execution timeout. They do not preempt synchronous
code, including blocking work inside a method that eventually returns a stage. The action-specific clock is
calculated after that return. A stage already completed when inspected can succeed even with zero remaining
wait time. There is no deadline check that interrupts setters, after-commit hooks or observers.

Cascades share the root's unit of work and deadline; entering a peer does not restart its machine-level
budget. Set defaults are installed only on asynchronous machines: those with declared async bindings, and those
whose calls can run an async edge implicitly, as the next paragraph explains. A synchronous-only root that cascades
to an async peer therefore has no root deadline supplied just by the set default. A timeout on
the peer action can still bound its wait, but an async guard on that path has no finite root budget. Choose
the entry point and timeout configuration deliberately when mixing synchronous and asynchronous machines.

The exit of a sub-machine shares the root's deadline too: it waits under the budget of the call's root, which is
the child machine's own when you called the child, and the exit edge's timeout can only shorten it. An exit runs
without any action of yours requesting it, and so do the edges it leads to: its failure routes, the exit that a
parent's terminal state selects one level up, and the exits of a sub-machine that the parent enters on the way.
Entering a bound state through the caller's own edge also starts its child's initial binding chain: a terminal
initial descendant selects its exit without an explicit cascade. The same rule covers those descendant exits,
including descendants started by an implicit edge. A registry therefore refuses a machine that declares no
`asyncTimeout` when any edge that its calls can run this way has an async action or guard:
`MachineRegistry.Builder.build()` throws `IllegalStateException` and names the edge.
A generated set installs its default on each such machine, and its `asyncTimeout(Machine.class, Duration)` accepts
one, so the set never builds a machine that the registry refuses. A cascade your action requests is explicit and
asks nothing of the kind.

Watch reactions are different: they execute after the triggering commit and start separate root
transactions with their own deadlines. The original operation waits for synchronous watch dispatch before
returning; its root wait budget does not impose one global deadline on the entire reaction chain.

### Timeout, cancellation and late completion

On an engine wait timeout, the operation throws `TransitionAbortedException`, pending graph-state changes
are discarded, and transaction-aware context operations become invalid. Through `machine.async`, that
exception completes the operation future exceptionally. Neither a recovery rule nor the declared `onFailure` route
is used for this timeout. The user's stage and the underlying service request are left running.
The engine waits through a callback registered on that stage with `handle`, which stays there until the stage
completes, as the JDK keeps every `handle` dependent; a wait that an interrupt or a pool's refusal ends leaves it
too. The engine remembers that callback, holding the stage weakly and by its identity alone, and a later wait for
the same stage, from any machine, waits on it instead of registering another. A stage that a single-flight cache
hands to every caller therefore carries one callback however many calls give up on it, plus one for each call that
was already waiting on it when the first gave up. Distinct stage objects are distinct stages: an action that returns
a new wrapper of a shared future on each call, such as `shared.thenApply(...)`, leaves on each wrapper one callback,
which the engine remembers too, until the shared future completes. A `CompletionStage` implementation of your own
keeps whatever its own `handle` keeps.

![After an engine wait times out, graph state does not commit. The user stage and external service can finish later, but that completion does not resume the aborted graph transaction.](images/async-late-completion.svg)

Three objects can be involved:

| What you stop or time out | Consequence |
| --- | --- |
| GraphMaquina's wait on an action or guard | Technical abort; no pending graph-state commit; the library does not cancel the user stage |
| The action's own stage is cancelled by its owner | Cancellation is treated as a technical action failure, not a handled business result |
| The outer operation future is cancelled or given a caller timeout | No cancellation link stops the running operation; do not assume it prevented commit |

In particular, `pending.cancel(true)`, `pending.orTimeout(...)` or a timed `pending.get(...)` is not a transaction
rollback API. The operation may already be running, may continue running and may commit after the caller stops waiting.
Interrupting an operation thread while it is waiting causes a technical abort, but GraphMaquina still does not cancel
the foreign stage. A `ForkJoinPool` that refuses to let its worker block for the wait, as one does when it cannot start
a spare worker in its place, causes a technical abort as well, whose cause is that pool's `RejectedExecutionException`,
unless a failed cascade has already poisoned the transaction, in which case the call ends in the failure that poisoned
it.

A late remote success can mean "the service processed the request, but the graph did not commit". Use a
stable request/idempotency key and a service-level deadline where needed. Reconcile uncertain remote results
before retrying an external side effect. GraphMaquina neither rolls back arbitrary field mutations made by
actions nor reverses HTTP calls, payments, messages or database writes performed outside its state setters.

### Contexts and completion callbacks

Capture the data required by the service while the action or guard is running. Prefer immutable values, as
`AsyncTicketActions` does with `String ticketId`, over passing a mutable entity or live context to a callback.

![Transaction-aware context operations belong to the original operation thread and live transaction. Service
callbacks receive immutable data and translate results without using peer views, cascades or the
flow.](images/async-context-boundary.svg)

`ctx.peer(...)`, `ctx.cascade(...)`, `ctx.cascadeAll(...)`, `ctx.flow()`, context-derived peer views and every
operation of a context's flow, `get`, `keys`, `entity`, `trail`, `put` and `remove`, require both the original
operation thread and a live transaction. A wrong thread produces `WrongThreadException`; use after invalidation produces
`StaleContextException`. After the root commits or aborts, its transaction-aware context is invalidated before
post-commit hooks run. Once invalidated, a context, its peer views and its flow let go of the call, so one retained
after the call keeps neither the call nor the values of its flow in memory. The flow a hook receives from its
`CommitContext` is checked the same way, against the thread running the hook and the hook's own run: it goes stale
once the hook returns or throws. The flow the caller receives once the call ended, in its outcome or its failure, is a
frozen copy without these checks.

Cascades also require that the commit has not begun and that no step is being rolled back. A retained action context
used for `cascade(...)` or `cascadeAll(...)` from a commit getter or setter, or from an identity's `equals` or
`hashCode` while a rollback calls it, throws `IllegalStateException` before it stages anything: the commit has already
fixed the changes it will write, and the rollback is undoing them. Reading peers or the flow does not stage another
hop, and a write to the flow from a commit getter or setter lands in the flow the call hands out.

Neither `thenApply` nor `thenApplyAsync` promises to run on that original operation thread. Even a callback
without the `Async` suffix may run on the service's completion thread. Do not hide a cascade or a write to the flow
in one of these callbacks. Read peers, write the flow or stage required cascades while on the operation thread;
after an external result, a separate facade invocation is a new transaction, not a continuation of the original
unit of work.

The context's simple entity/from/to/depth getters do not enforce these lifetime/thread checks. That does not
make the entity immutable or safe for concurrent access. Do not mutate the state property in an action or a
late callback; GraphMaquina owns graph-state writes during commit.

Both `screen(entity, verdict)` and `machine.async(() -> machine.screen(entity, verdict))` carry the value.
Input getters are simple data accessors like entity/from/to/depth; the library neither copies nor freezes
the value. Capture immutable data for asynchronous callbacks. Peer views, cascades and the flow still require the
original execution thread and a live transaction.

### Executors and machine lifetime

Each machine uses a virtual-thread-per-task executor by default. `machine.async(supplier)` submits the whole
supplier to that executor. It does not automatically create one transaction spanning several operations
inside the supplier: each facade operation starts its own root. Use `cascade` inside an action when multiple
machines must share one graph transaction.

A set can use an application-owned executor instead. For this example, import
`java.util.concurrent.Executors` and `java.util.concurrent.ExecutorService`:

```java
try (ExecutorService operations = Executors.newVirtualThreadPerTaskExecutor();
     AsyncTicketMachines machines = AsyncTicketMachinesRuntime.builder(
             Duration.ofSeconds(5), new AsyncTicketActions(reviews))
             .asyncExecutor(operations)
             .build()) {
    Outcome<Ticket, TicketState, TicketTransition> submitted = machines.tickets()
            .async(() -> machines.tickets().submit(ticket)).join();
    System.out.println(submitted.recordedState());
}
```

The example waits before closing. The executor is closed by the application's try-with-resources statement,
not by the machine set. With the defaults, closing a set closes its owned executors and waits for their
submitted work. An interrupt of the thread that closes the set, before or while it waits, reaches every owned
executor the set has not finished closing, not only the one it waits for: each interrupts the threads of its
operations, so a call waiting for a stage aborts as interrupted, and `close()` still waits for them to end, then
returns with the thread interrupted. Closing an owned executor from one of its own operations would wait for that
operation forever, so `close()` throws `IllegalStateException`, before it closes anything, on a thread that runs
such an operation, an asynchronous call or the supplier given to `async(...)`, whatever calls it there: an action, a
hook or a watch reaction of the call, a call a hook starts, or a dependent that completing the operation's future runs
on that thread. The set stays open; close it from another thread. With a custom executor, the caller owns shutdown
and must keep the machines and any service dependencies alive until operations finish. Closing the registry is not a
general cancellation barrier for synchronous calls or work submitted through a still-running custom executor. A
rejected submission yields an exceptionally completed future.

Avoid using the same small fixed executor for operation workers and service completions scheduled by
those operations. If every worker is blocked waiting for a service stage whose completion is queued behind
those workers, the executor can be starved. Keep service execution independent or provision an execution
model that does not block the resources its completions need. A custom direct executor such as `Runnable::run`
runs inline and removes the offloading behavior shown for the default executor.

A root that runs on a `ForkJoinPool` worker, such as one submitted through
`asyncExecutor(ForkJoinPool.commonPool())`, waits for its stage without running other tasks on its thread. If
the stage's own work is a task queued on that pool, another worker has to take it: an idle one, a spare the pool
starts, or one that frees up. While every other worker is busy, or blocked where the pool cannot see it, as on a
lock or a plain latch, the wait goes on, up to its timeout. When every other worker is blocked where the pool
sees it, as in `CompletableFuture`'s `get` or `join` or in another GraphMaquina wait, the pool starts a spare, if
its size allows.

A call submitted through `async(...)` is queued as a plain task, never as one of the asynchronous tasks that
`CompletableFuture`'s `get` and `join` run on a waiting `ForkJoinPool` worker, so a callback that waits there never
runs it on its own thread. While such a call is queued on the waiting worker, another worker has to take it, as for a
stage's task, and so has a `CompletableFuture` task queued behind it: a callback that waits for either waits, under no
timeout but its own, while every other worker is busy or blocked where the pool cannot see it. Two waits can still run
a queued call on the waiting thread, where the call fails with `ReentrantTransitionException`: a worker that joins a
`ForkJoinTask` may run a task queued by another worker that stole work from it, and waiting for a pool to quiesce,
through `ForkJoinTask.helpQuiesce()` or `ForkJoinPool.awaitQuiescence`, runs the pool's queued tasks on any thread.
Given `ForkJoinPool.commonPool()` while the common pool's parallelism is below two, a machine runs each call on a new
thread instead, as `CompletableFuture.supplyAsync` does with that pool on JDK 21. On JDK 25, `CompletableFuture`
raises the common pool's parallelism from 0 to 2 when its class is initialized, so a machine built after that runs
its calls on the pool. Start roots off `ForkJoinPool` workers, or give asynchronous work its own executor.

Multiple virtual threads do not serialize access to a shared entity. Two concurrent operations on the same
ticket need an application-level concurrency policy. The commit precheck detects some state conflicts; it
is not a lock or a compare-and-set transaction with your database. See
[concurrency](#concurrency-identity-and-persistence).

### Test async behavior

Use controllable futures rather than timing guesses. For each case below, build a fresh set and entity,
complete the service future deliberately, then inspect both the outcome/exception and the entity state.
For the action/`approved` cases, first let `submit` succeed so the ticket begins that check in `REVIEW`;
a fresh ticket starts in `OPEN`, where no edge carries `APPROVE`.

| Case | Service behavior | Expected observation |
| --- | --- | --- |
| Async guard passes | `canStart` completes `true` | `submit` returns `Applied`; state is `REVIEW` |
| Async guard rejects | `canStart` completes `false` | `submit` returns `Rejected` with kind `GUARD`; state stays `OPEN` |
| Action succeeds | `approved` completes `true` | `approve` returns `Applied`; state is `DONE` |
| Business decline | `approved` completes `false` | `approve` returns `Failed`; state is `FAILED` |
| Declared action exception | Stage fails with `IOException` | The `DECLINE` edge commits `FAILED` |
| Unexpected action exception | Stage fails with `IllegalStateException` | Technical abort; state remains `REVIEW` |
| Pending action exceeds wait budget | Leave its stage incomplete | `TransitionAbortedException`; state remains `REVIEW`; service stage is not cancelled |
| Service finishes after wait timeout | Complete that same stage later | No late graph-state commit |
| Guard stage throws | Fail `canStart` exceptionally | Technical abort; state stays `OPEN`; action is not called |

Also cover hook failures and concurrent-entity policy in the application. The repository's
[async runtime tests](../graphmaquina-core/src/test/java/io/github/graphmaquina/runtime/AsyncTest.java) and
[context lifetime tests](../graphmaquina-core/src/test/java/io/github/graphmaquina/runtime/SingleMachineTest.java)
provide focused examples of deadline and ownership behavior.

## Build and manage machine sets

Declare one top-level set interface extending `Machines`, with one no-argument accessor per machine. The
reference set is:

```java
@StateGraphs
public interface ShopMachines extends Machines {
    Orders orders();
    Items items();
}
```

The generated builder takes the instance binding hosts used by the machines. Its signature is generated from
the set's declarations; inspect the generated method or completion when dependencies change. It takes the hosts in
the order of the set's accessors, after the shared timeout when it has one, a host at the first accessor whose machine
uses it: `ShopMachines` above gets `builder(Duration, OrderActions, ItemActions)`. Reordering the accessors can
therefore reorder its parameters, a source-incompatible change for every caller of `builder(...)`. GraphMaquina
does not instantiate service classes or discover dependency-injection beans.

| Builder option | Purpose / default |
| --- | --- |
| `asyncTimeout(Machine.class, Duration)` | Override timeout for one asynchronous machine; generated only for a set that has one, as is the timeout argument of `builder(...)` |
| `asyncExecutor(Executor)` | Supply the executor used by `async` calls, where `ForkJoinPool.commonPool()` with a parallelism below two runs each call on a new thread instead; otherwise each machine owns a virtual-thread executor |
| `recordTrail()` | Record the [trail](#record-the-calls-trail) of every call of the set on `InstantSource.system()`; off by default |
| `recordTrail(InstantSource)` | Record that trail on the given clock instead |
| `maxCascadeDepth(int)` | Bound nested cascades and sub-machine exits; default `10`, minimum `1`, root depth is `0` |
| `maxWatchChain(int)` | Bound successive watch reactions; default `10`, minimum `1` |
| `observer(MachineObserver)` | Observe every call of the set; observers add up, notified in the order given |
| `build()` | Construct the registry and facades |

Each setting is checked where it is given: a `maxCascadeDepth` or `maxWatchChain` below `1` throws
`IllegalArgumentException`. The builder is single-use: once `build()` succeeds, every method throws
`IllegalStateException`.

Both limits count levels that run on the stack of the calling thread: each cascade, sub-machine exit and watch
reaction runs one level deeper, so that stack bounds them too. As a measure, with trivial actions on JDK 21, a thread
of 1 MiB held between about 250 and 1,800 cascade levels or watch reactions, and one of 8 MiB between about 2,300 and
16,000: the fewest over the first calls of a JVM, while the JIT is still compiling the engine's code, and the most
once it has optimized it. How many exactly depends on the JIT, on the platform and on the frames of your own
callbacks. A call that goes deeper than its thread's stack meets a `StackOverflowError`, which reaches your actions
on its way up, as [cascade a transition](#cascade-a-transition) says, and otherwise ends the call; in a watch chain,
the root and the reactions before the one that overflowed have committed already. The registry and the thread are not
left unusable by it: later calls run as before. Keep the limits within what the stacks of your threads hold, or run
deep calls on threads with a larger stack.

Close the set with try-with-resources or your application's shutdown lifecycle. `close()` closes executors
owned by the set and waits for submitted operations to finish. An interrupt of the closing thread interrupts the
operations of every owned executor the set has not finished closing, and `close()` refuses to run on the thread of
one of those operations, as [executors and machine lifetime](#executors-and-machine-lifetime) says. An executor you
supply remains your responsibility. Avoid sharing a short-lived set across requests that outlive its scope.

For dependency injection, register the generated set as an application-managed component using your existing
`OrderActions` and `ItemActions` instances, then inject its facades into services. Arrange for `close()` at
shutdown. There is no required Spring dependency or GraphMaquina-specific container integration.

## Read peers and cascade transitions

![A cascade shares the root unit of work and deadline before commit. A watch reacts after peer commit in a new transaction and cannot undo that peer commit.](images/cascade-vs-watch.svg)

Register interacting machines in the same set. Inside a guard or action, use the peer's facade class so
Java infers its entity and enum types:

```java
@GraphGuard
public GuardResult allItemsPacked(GuardContext<Order, OrderStatus, OrderTransition> ctx) {
    return ctx.peer(Items.class).allIn(ctx.entity().items(), ItemStatus.PACKED)
            ? GuardResult.pass()
            : GuardResult.reject("there are unpacked items");
}
```

This is a method from the shop's `OrderActions`. `PeerView.stateOf(peer)` reads pending state from the current
unit of work before falling back to the entity's getter. Calling the entity getter directly during a cascade
can therefore miss an uncommitted state change.

| Peer helper | Empty collection | Nonempty collection |
| --- | --- | --- |
| `allIn(peers, state)` | `false` | Every peer must be in the state |
| `anyIn(peers, state)` | `false` | At least one peer must be in the state |
| `noneIn(peers, state)` | `true` | No peer may be in the state |

### Cascade a transition

Actions can change peers as part of their own unit of work:

```java
@GraphAction
public ActionOutcome cancel(TransitionContext<Order, OrderStatus, OrderTransition> ctx) {
    ctx.cascadeAll(Items.class, ctx.entity().items(), ItemTransition.CANCEL,
            CascadePolicy.COLLECT);
    return ActionOutcome.ok();
}
```

The cascade names `ItemTransition.CANCEL`, an identifier from the item's own catalogue, not the `CANCELLED`
state. javac refuses a constant of the order's catalogue here, and the engine resolves that identifier from
each item's current state: `PENDING` and `PACKING` both declare it, `PACKED` does not. The shop's own `cancel`
first copies a batch into the call's flow, for the action of the items' `CANCEL` edge, as
[the call's flow](#the-calls-flow) shows.

For one entity, use `ctx.cascade(Items.class, item, ItemTransition.CANCEL)`. Machine-key overloads also exist
for lower-level integrations.

A cascade resolves the peer's edge by identity and runs its guards and action. Its success stages a change; it
does not commit independently. The root commits after all required work succeeds. There is no convergence by
destination: a nested request on an entity that is already in flight is a cascade cycle even when it asks for
the transition that entity is running, and once the entity is no longer in flight the request is resolved
from its pending state like any other.

| Policy | Behavior |
| --- | --- |
| `FAIL_FAST` | Stop when a cascade fails. |
| `COLLECT` | Try peers under savepoints and collect the transition aborts of the call; if any were collected, poison the transaction with a `CascadeException` that aggregates them and throw it into the action that cascaded. Any other failure stops it at once, and so does an element whose sub-machine binding check could not read a parent or a child, such as a parent whose state is null, an element whose step a nested cascade poisoned with a failure that is no failure of the call, or an identity whose `equals` or `hashCode` throws while a failed element is rolled back. |

`COLLECT` is not partial commit. A nested transition that is rejected, because no edge carries it, a guard rejected it
or a sub-machine refused it, is an abort rather than an ordinary root-level `Rejected` result. A handled domain
failure can select the child's declared failure route.
Any failure that leaves a cascade poisons the unit of work. A failure of the call itself, such as an abort in the peer's
step, a cycle or the depth limit, reaches your action as it is. The `TransitionAbortedException` Javadoc says where the
abort of a nested step goes. Anything else reaches your action as a `TransitionAbortedException` that names the cascade
and carries the failure as its cause: an engine exception that describes no call, such as an unknown machine or an
ambiguous identity, or a throwable other than an `Error` from a callback the engine does not wrap, such as an identity
function. An `Error` poisons through such an abort too, but reaches your action as itself, with any `Error` that
printing it threw as suppressed. A `VirtualMachineError`, such as a `StackOverflowError` or an `OutOfMemoryError`,
reaches your action at once, before that abort exists, since building it could need the stack or the memory that ran
out. If your action catches it and goes on, the engine records the poison on your action's step, once the stack has
unwound to it: as soon as your action reads a peer or cascades again, which then throws a
`PoisonedUnitOfWorkException`, and at the latest when it ends, unless an `Error` it throws as itself ends it, which
propagates. That poison is a `TransitionAbortedException` that names your action's step and carries no cause, and a
transaction that was poisoned before keeps its first poison. Catching an `Error` in your action
does not make the transaction safe to commit. Once the engine has your action's outcome, it throws the failure that
poisoned the transaction instead of applying it. It does the same for a failure of the call that your action throws or
completes its stage with, and, unless the action is a recovery action, for a throwable that its `failsOn` or one of its
recovery rules accepts, which an `Error`, a `CancellationException` or a `StateMachineException` never is; no failure
route is taken, and a recovery action's route was already taken. The engine's own aborts of the action step, for a null
outcome or stage or a wait that timed out, was interrupted or was refused, also end in the failure that poisoned the
transaction. Your action can still end the call in another failure, such as an abort that carries a throwable no
declared type accepts, or an `Error` it throws as itself. A cascade that names an unknown machine poisons too, by key or
by facade type, even over an empty collection. A refused argument poisons nothing: a null peer, a null element, which
`cascadeAll` refuses before any element runs, or an identifier from another machine's catalogue, and neither does a
cascade refused because the commit has begun or a step is being rolled back. A `VirtualMachineError`, such as a
`StackOverflowError`, that strikes while these checks run poisons nothing either: the cascade has not started.
`cascadeAll` copies its
collection once, before any element runs, and never reads it again, so elements added or removed while the cascade runs
change neither which elements run nor their order; an exception or an `Error` the collection throws while it is copied
propagates as it is and poisons nothing, even when the machine is unknown. Use `ctx.cascade` instead of calling another
facade operation from inside an action: nested facade entry points on the action's own thread violate the runtime's
reentrancy rules. A call submitted through `async(...)` runs separately on the executor's thread, unless it runs on the
action's own thread, through an executor that runs tasks inline or a wait that runs a `ForkJoinPool`'s queued tasks,
as [executors and machine lifetime](#executors-and-machine-lifetime) lists, in which case the returned future fails
with the `ReentrantTransitionException`.

No declared type accepts an `Error`, a `CancellationException` or a `StateMachineException`. For a throwable that a
declared type accepts and that an action other than a recovery action throws or completes with, the engine checks the
poison before it reads the throwable's message, for synchronous throws and exceptional completions of completed or
pending stages alike. Exceptional stage completions retain their original throwable through the wait, so the engine's
own reading of the message never comes first. The JDK may already have read that message, for example when `supplyAsync`
wrapped the throwable in a `CompletionException`. When the edge's action, other than a recovery action, throws or
completes with a throwable that no declared type accepts, a `CancellationException` included, and that is neither an
`Error` thrown as itself nor a failure of this call, the engine aborts the call with a message composed from that
throwable's string, which may read its message, whether or not the transaction is poisoned. A throwable that a recovery
action throws or completes with is never routed, whatever the recovery edge declares: an `Error` thrown as itself
propagates; the engine throws a failure of this call as itself or, when the transaction is poisoned, the failure that
poisoned the transaction; and on any other throwable the engine aborts the call with a message composed from the
recovery action's name and the original failure's reason, never from that throwable's string, whether or not the
transaction is poisoned. That abort carries the throwable as cause and holds as suppressed the throwable that selected
the failure route, when a throwable selected it and is not the same one.

`@GraphPeers` is for generating the vocabulary of a pair: the annotations that declare the owner's watches and
sub-machines over the peer. A runtime peer read or cascade alone does not require a pair that no watch or
sub-machine uses.

## Watch other machines

A watch reevaluates an owner after one of its peers changes state. It declares both directions of the
relationship: owner → peer collection and peer → optional owner.

The shop has orders and items. An order watches its items while it is `PAID`, and ships when all are `PACKED`.

![After the last item commits PACKED, the PAID order is reevaluated in a new transaction. If all peers are PACKED, its normal shipping edge runs. A failed reaction does not undo the item commit.](images/watch-reactions.svg)

This excerpt belongs on the shop's `Orders` interface, alongside its definition, policies and operations:

```java
@GraphPeers(Items.class)
@OrdersItems.Watch(
        state = PAID,
        peers = OrderRefs.Items.class,
        owner = ItemRefs.Order.class,
        rules = @OrdersItems.When(allIn = ItemStatus.PACKED, transition = OrderTransition.SHIP))
```

`OrdersItems` is generated from the pair. `transition` is an `OrderTransition`, the owner's own catalogue;
`allIn` is an `ItemStatus`, the peer's states. Mixing those enums is a javac type error. The rule executes the
edge keyed by `(PAID, SHIP)`, including its guard, action and hook — never another edge that also reaches
`SHIPPED`. That edge must exist from the watched state, or the build fails with `SG401`.

### Ordered rules

A watch has one or more rules, and the first matching rule wins. Each rule uses exactly one of:

| Rule element | Condition |
| --- | --- |
| `allIn = ItemStatus.PACKED` | Nonempty peers, all in the given state |
| `anyIn = ItemStatus.CANCELLED` | At least one peer in the given state |
| `noneIn = ItemStatus.PACKING` | No peer in the given state; true for empty peers |
| `condition = OrderConditionsRefs.Ready.class` | A marked custom predicate |

Every rule also names the `transition` the owner executes when it holds.

Each built-in filter takes one peer state; it is not a set-membership expression. For a more complex
predicate, add a host such as this alongside the shop's classes:

```java
package io.github.graphmaquina.example.order;

import io.github.graphmaquina.annotation.GraphCondition;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemStatus;
import java.util.Collection;

public final class OrderConditions {
    @GraphCondition
    public boolean ready(Order owner, Collection<? extends Item> peers,
                         PeerView<Item, ItemStatus, ItemTransition> view) {
        return !peers.isEmpty() && view.allIn(peers, ItemStatus.PACKED);
    }
}
```

Replace the built-in rule with
`@OrdersItems.When(condition = OrderConditionsRefs.Ready.class, transition = OrderTransition.SHIP)`.
Regenerate and supply the new
`OrderConditions` instance to the resulting set builder. Custom conditions are synchronous predicates.

With this replacement in the reference shop, the generated builder becomes:

```java
ShopMachines shop = ShopMachinesRuntime.builder(
        Duration.ofSeconds(10), new OrderConditions(), new OrderActions(gateway, carrier), new ItemActions())
        .build();
```

Close this set as described in [machine lifecycle](#build-and-manage-machine-sets). Binding changes can change
the generated builder's parameter list and order; regenerate before updating the application's wiring.

A machine can have at most one watch per owner state. Multiple rules belong inside that watch. Declare only
pairs that a watch or a sub-machine uses; duplicate or unused `@GraphPeers` entries are errors.

### Triggering and observing watches

After a root commit, the runtime finds watched owners from peers whose final state differs from their original state.
Intermediate hops that return a peer to its original state do not trigger dispatch. It reevaluates an owner only if it
is currently in the watched state and deduplicates owners within that dispatch. Each reaction is a new root transaction.
A reaction failure does not roll back the peer commit that triggered it.

The watch's `owner` and `peers` readers (`ownerOf` and `peersOf` in the DSL) and its conditions run while the engine
evaluates the watch, before that root starts, and may not start a transition on their own thread: `execute` or
`reevaluate` called from one of them throws `ReentrantTransitionException`; a call submitted through `async(...)` runs
as a separate call on the executor's thread, unless it runs on the function's own thread, through an executor that
runs tasks inline or a wait that runs a `ForkJoinPool`'s queued tasks, in which case the returned future fails with
that exception as its cause. Inside a reevaluation, a refusal that one of these functions lets through ends
the reevaluation in a `TransitionAbortedException` of phase `RESOLVE` that carries it: a reaction reports the abort as
`Reaction.Aborted` and a direct `reevaluate` throws it to its caller. An owner lookup runs before the reevaluation, so
its reaction carries the refusal itself. A `peers` reader that returns `null` instead of a collection, or an `owner`
reader that returns `null`, whether it declares an `Optional` or the owner entity, is refused the same way, with a
`NullPointerException` that says it returned null, and no condition runs over a `null` collection.

Changing a collection or setting a field directly does not emit a transition event. If your application
changes relationships outside a peer transition, explicitly request reevaluation where appropriate:

```java
Outcome<Order, OrderStatus, OrderTransition> outcome = shop.orders().reevaluate(order);
```

No watch produces `Outcome.Ignored` with `IgnoreReason.NO_WATCH`, and a watch whose rules all fail produces
`IgnoreReason.NO_RULE_MATCHED`. These are the only two ways an `Ignored` is produced. A matching rule can still be
rejected by the named edge's guards, or as `DETACHED` when the owner is a sub-machine whose parent does not hold it. Rules
are not polled on a timer, and creating or paying an owner does not
automatically mean all of its existing peers changed state.

Cross-JAR watches are not supported yet. The owner and peer APIs must be generated in the same compilation.
A peer in another module or dependency JAR is rejected with `SG608`. Track the enhancement in
[issue #1](https://github.com/graphmaquina/graphmaquina/issues/1).

## Compose machines

![The booking machine binds the same payment machine in DEPOSIT and in BALANCE. BOOK enters DEPOSIT and starts the
deposit at AUTHORIZING; a captured deposit exits through DEPOSIT_PAID to BALANCE, which starts the balance; a captured
balance exits through BALANCE_PAID to CONFIRMED; DECLINED and VOIDED exit to CANCELLED. CANCEL leaves either state from
its border and runs no exit. The payment machine knows no parent, and the subscription machine binds it
too.](images/submachine-binding.svg)

A state can hold a whole machine of its own: its sub-machine, or child. The repository's billing example, in the
package `io.github.graphmaquina.example.billing`, binds one payment machine three times. A booking pays a deposit in
`DEPOSIT` and then a balance in `BALANCE`, and a subscription pays each renewal in `RENEWAL`. The payment machine knows
no parent: every binding is declared on the parent's side, so one machine can be bound in several states of several
machines. Three rules make the contract:

- An edge that enters a bound state starts the child at the initial state of the child's graph, in the same unit of
  work as that edge. Its `childOf` must answer a child then: an empty answer aborts the step that enters before its
  action runs, with a `TransitionAbortedException` such as `Booking/BookingStatus entered DEPOSIT with no
  Payment/PaymentStatus`, and the call commits nothing.
- A parent holds its child while it is in the bound state, or leaving it, and its `childOf` answers that very child.
  A call to a child is refused as `DETACHED` when a binding finds its parent but no parent holds it: the parent has
  left the state, or holds another child or none there. A child for which no binding finds a parent is a free machine
  and runs as usual.
- When a step of a bound child ends in one of its terminal states, that state selects the parent's exit. The exit runs
  in the same unit of work, one level below that step, and commits with the child. The binding is judged when the
  child's step begins: when the step ends, the exit runs unless the parent is in flight, or the step itself, through
  its action, moved the parent out of the bound state or entered it again. The parent's state and `childOf` are not
  read again, so replacing the parent's child during the child's own action does not cancel the exit; to keep it from
  running, move the parent out of the state, or restart it there, from that action.

### Declare a sub-machine

The child is an ordinary machine. `Payments` declares `AUTHORIZING` and three terminal states, `CAPTURED`, `DECLINED`
and `VOIDED`, and nothing in it names a booking or a subscription. The parent declares the pair with `@GraphPeers`, as
for a watch, and binds the child with the pair's generated `Submachine`. This is the whole `Bookings` interface, which
imports the `BookingStatus` constants statically:

```java
/**
 * The booking machine: {@code DEPOSIT} and {@code BALANCE} each hold a payment of {@link Payments}, the same machine
 * bound twice. The terminal state each payment reaches selects the exit of its state, and {@code CANCEL} interrupts
 * either one. The processor generates BookingsRuntime.
 *
 * <p>Exits are declared on the interface, not on operations: an exit runs only when its payment ends, so no
 * operation may declare one.</p>
 */
@GenerateGraphApi
@GraphPeers(Payments.class)
@BookingsGraph.Definition(initial = REQUESTED, terminals = {CONFIRMED, CANCELLED},
        state = BookingRefs.Status.class, id = BookingRefs.Id.class)
@BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is booked first")
@BookingsGraph.StatePolicy(state = DEPOSIT,   otherwise = "the deposit payment decides")
@BookingsGraph.StatePolicy(state = BALANCE,   otherwise = "the balance payment decides")
@BookingsGraph.Transition(id = BookingTransition.DEPOSIT_PAID, from = DEPOSIT, to = BALANCE,
        action = BookingActionsRefs.HoldRoom.class, onFailure = BookingTransition.NO_ROOM)
@BookingsGraph.Transition(id = BookingTransition.NO_ROOM, from = DEPOSIT, to = CANCELLED)
@BookingsGraph.Transition(id = BookingTransition.DEPOSIT_DECLINED, from = DEPOSIT, to = CANCELLED)
@BookingsGraph.Transition(id = BookingTransition.BALANCE_PAID, from = BALANCE, to = CONFIRMED)
@BookingsGraph.Transition(id = BookingTransition.BALANCE_DECLINED, from = BALANCE, to = CANCELLED)
@BookingsGraph.Transition(id = BookingTransition.PAYMENT_VOIDED, from = {DEPOSIT, BALANCE}, to = CANCELLED)
@BookingsPayments.Submachine(state = DEPOSIT, child = BookingRefs.Deposit.class,
        parentLookup = BillingLookupsRefs.BookingOf.class,
        exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = BookingTransition.DEPOSIT_PAID),
                 @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = BookingTransition.DEPOSIT_DECLINED),
                 @BookingsPayments.Exit(on = PaymentStatus.VOIDED, transition = BookingTransition.PAYMENT_VOIDED)})
@BookingsPayments.Submachine(state = BALANCE, child = BookingRefs.Balance.class,
        parentLookup = BillingLookupsRefs.BookingOf.class,
        exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = BookingTransition.BALANCE_PAID),
                 @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = BookingTransition.BALANCE_DECLINED),
                 @BookingsPayments.Exit(on = PaymentStatus.VOIDED, transition = BookingTransition.PAYMENT_VOIDED)})
public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {

    @BookingsGraph.Transition(id = BookingTransition.BOOK, from = REQUESTED, to = DEPOSIT)
    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);

    @BookingsGraph.Transition(id = BookingTransition.CANCEL, from = {DEPOSIT, BALANCE}, to = CANCELLED,
            action = BookingActionsRefs.Cancel.class)
    Outcome<Booking, BookingStatus, BookingTransition> cancel(Booking booking);
}
```

`Submachine` is repeatable, so one machine can bind the same child in several states, each binding with its own way to
the child and its own exits. Its elements, and those of `Exit`, take the enums of their side of the pair, so a state or
an identifier of the wrong machine is a javac error:

| Element | Meaning |
| --- | --- |
| `state` | The bound state of the parent. A terminal state holds no sub-machine, and another state holds at most one (`SG803`). |
| `child` | A property of the parent that returns `Optional` of the child's entity: `Booking.deposit()` and `Booking.balance()` return `Optional<Payment>`. |
| `parentLookup` | A `@GraphLookup` method that finds the parent of a child entity, described below. |
| `parent` | Instead of `parentLookup`, a property of the child that returns `Optional` of the parent's entity. A child over another entity than the parent's needs `child` and exactly one of `parent` or `parentLookup` (`SG806`). |
| `exits` | One `Exit(on, transition)` per terminal state of the child: the identifier of the parent that the terminal state selects, which needs an edge from the bound state (`SG801`, `SG802`). Two terminal states may select the same identifier, as `DECLINED` and `VOIDED` do in `Subscriptions` below. |

An identifier that an exit names runs from the bound state only when the child ends. No `onFailure`, recovery rule or
rule of that state's watch may name it from there, and no operation of the facade may execute it at all, since an
operation requests its identifier from whichever state the machine is in (`SG804`). The exits of `Bookings` are
therefore declared on the interface, and `book` and `cancel` are its only operations. From any other state, the same
identifier is an ordinary edge, unless a binding of that state maps it too. A machine's sub-machines never contain the
machine again (`SG805`). [Structural rules](#structural-rules) and
[graph and declaration diagnostics](#graph-and-declaration-diagnostics) list every check.

### Find the parent with a lookup

A call to a payment has to know whether a parent holds it, so each binding needs the way back from the child.
`@GraphLookup` marks a method of signature `Optional<E> name(P child)`, static or instance, on any class. The
generator exports it as a symbol of that class's `Refs`, such as `BillingLookupsRefs.BookingOf`, which `parentLookup`
names. These are the two lookups of `BillingLookups`, which keeps the bookings and subscriptions it registered in two
maps by payment id, where an application would ask its repository:

```java
@GraphLookup
public Optional<Booking> bookingOf(Payment payment) {
    return Optional.ofNullable(bookings.get(payment.id()));
}

@GraphLookup
public Optional<Subscription> subscriptionOf(Payment payment) {
    return Optional.ofNullable(subscriptions.get(payment.id()));
}
```

An empty `Optional` means that this binding finds no parent. Lookups and the `child` and `parent` properties are
synchronous and run inside the call. One that returns `null` instead of an `Optional` aborts the call, and `execute`
or `reevaluate` called from one of them throws `ReentrantTransitionException`, which aborts the call too.

### Build the set

`BillingMachines` lists `Bookings`, `Subscriptions` and `Payments`, as `ShopMachines` lists the shop's machines. Its
generated builder takes the instance hosts of every machine, here the booking actions and the lookups:

```java
BillingLookups lookups = new BillingLookups();
BillingMachines billing = BillingMachinesRuntime.builder(Duration.ofSeconds(10), new BookingActions(desk), lookups)
        .build();
```

`desk` is the application's `RoomDesk`, the port that `holdRoom`, the action of `DEPOSIT_PAID`, asks for a room. The
first argument is the shared timeout of the set's asynchronous machines, which
[asynchronous exits](#asynchronous-exits) explains. The set builds the payment definition once and passes that one
instance to the payment machine and to the companions of both parents: `BookingsRuntime.graph(...)` and
`SubscriptionsRuntime.graph(...)` take the definition of each machine they bind before their hosts. A registry refuses
a parent whose child machine it does not hold, or holds built from another definition:
`MachineRegistry.Builder.build()` throws `IllegalStateException`. A generated set always registers the child with the
very definition its parents bind; the check matters when you assemble machines yourself, as with the
[programmatic DSL](#programmatic-dsl).

### Enter and exit

A booking holds its two payments, and `lookups.register(booking)` lets `bookingOf` find it from either one:

```java
Booking booking = new Booking("b1", new Payment("b1-deposit"), new Payment("b1-balance"));
lookups.register(booking);
Payment deposit = booking.deposit().orElseThrow();

Outcome<Booking, BookingStatus, BookingTransition> booked = billing.bookings().book(booking);
Outcome<Payment, PaymentStatus, PaymentTransition> depositPaid = billing.payments().capture(deposit);
```

`book` enters `DEPOSIT`, so its call also starts the deposit at `AUTHORIZING`. `booked.starts()` holds one
`SubmachineStart`: the payment machine and the deposit, the state the deposit had and the one it started in, and the
booking with its bound state `DEPOSIT`. The start is logged right after the edge that enters, before that edge's action
runs, so an action that cascades into the child finds it at its initial state. A start is no hop: it is not in
`records()`, and it runs no hook. A child whose initial state is terminal enters and exits in that same call.

`capture` is a call of the payment machine. Its step ends in `CAPTURED`, a terminal state, so the call runs the
booking's exit `DEPOSIT_PAID` from `DEPOSIT`, one level deeper, with the exit's guards and action. `depositPaid` is the
payment's own outcome, an `Applied` that moved the payment to `CAPTURED`. Its `records()` hold the capture at depth 0
and `DEPOSIT_PAID` at depth 1, in log order, and its `starts()` hold the balance, because the exit entered `BALANCE`,
which binds a payment too. Capturing the balance then exits through `BALANCE_PAID` to `CONFIRMED`. The exits belong to
the calls that ran them: an observer sees three calls in all, the booking's `book` and the two captures. When an exit
leaves a parent in a terminal state that a binding of its own parent maps, the next exit runs one more level up. Each
level counts for `maxCascadeDepth`. A child that ends while its parent is still running the edge that entered the
state, as when that edge's action captures the payment, exits once that edge's step is over. That exit runs one level
below the edge's step, at the same depth as the capture, and counts for `maxCascadeDepth` at that depth.

An exit is an ordinary edge of the parent, so its failure routes apply as on any edge. When the desk answers that no
room is left, `holdRoom` fails: the booking takes the route `NO_ROOM` to `CANCELLED`, the whole call commits, and the
capture's outcome is still `Applied`. The payment stays `CAPTURED`, since the route moves the booking and not the
payment, and the balance is not started: the edge that would have entered `BALANCE` was rolled back with its start. An
exit that a guard rejects aborts the call with a `TransitionAbortedException` whose message includes
`rejected inside the exit of DEPOSIT by guard ...`, and one that fails with no route ends it in an
`UnhandledFailureException`. Either way nothing is written, the capture included. As with a cascade, what an action did
outside the machines, such as asking the desk for a room, is not undone.

### Interrupt, detach and reserve

A parent can leave a bound state by an edge of its own while the child is still running. `cancel` leaves `DEPOSIT` or
`BALANCE` for `CANCELLED`, and its action voids the payment of the state the booking leaves:

```java
@GraphAction
public ActionOutcome cancel(TransitionContext<Booking, BookingStatus, BookingTransition> ctx) {
    Booking booking = ctx.entity();
    Payment payment = ctx.from() == BookingStatus.DEPOSIT ? booking.deposit().orElseThrow()
            : booking.balance().orElseThrow();
    ctx.cascade(Payments.class, payment, PaymentTransition.VOID);
    return ActionOutcome.ok();
}
```

While the booking leaves the state, the binding still holds the payment, so the cascade runs. The booking is in flight,
so the voided payment runs no exit: `PAYMENT_VOIDED` does not run, and the booking ends in `CANCELLED` by its own edge.
An interruption whose action leaves the child alone leaves it in the state it had reached.

Once the booking is cancelled, its payments are detached. `billing.payments().capture(deposit)` then returns a
`Rejected` outcome with the code `DETACHED` and the reason `no parent holds it in DEPOSIT or BALANCE`, which names the
bound state of every binding that found the booking. No guard and no action runs, and the check comes before the edge is
looked up, so a detached child is refused as `DETACHED` even for an identifier it has no edge for. Inside a cascade the
refusal aborts the call, like any nested rejection. Entering a bound state again restarts its child at the initial
state, whatever state the child had reached: a subscription charges the same renewal payment in every period.

The exits are reserved. In the bound state, only the end of the child runs an identifier that an exit names: a request
for `DEPOSIT_PAID` from `DEPOSIT` by any other path, such as a cascade or `StateMachine.execute` on a registry you
assembled, is refused as `RESERVED_EXIT`, with the reason `DEPOSIT_PAID leaves DEPOSIT only when its sub-machine ends`,
and aborts the call inside a cascade. Inspection still lists the edge: `transitionsFrom(BookingStatus.DEPOSIT)` includes
`DEPOSIT_PAID`.

A child entity has one active parent at a time. When two bindings each find a parent that holds the child in its bound
state, or while leaving it, a call to the child aborts with an `AmbiguousParentException` as its cause. A parent that
leaves one bound state for another that binds the same child holds it only in the state it enters, whose entry has just
restarted the child, so the action of that edge can cascade into the child.

### Reuse a machine

Each binding of `Bookings` reaches its own payment, so the booking holds its deposit only in `DEPOSIT` and its balance
only in `BALANCE`. `Subscriptions` binds the same machine once more, with exits of its own:

```java
@GraphPeers(Payments.class)
@SubscriptionsPayments.Submachine(state = RENEWAL, child = SubscriptionRefs.Renewal.class,
        parentLookup = BillingLookupsRefs.SubscriptionOf.class,
        exits = {@SubscriptionsPayments.Exit(on = PaymentStatus.CAPTURED, transition = SubscriptionTransition.RENEWED),
                 @SubscriptionsPayments.Exit(on = PaymentStatus.DECLINED, transition = SubscriptionTransition.LAPSE),
                 @SubscriptionsPayments.Exit(on = PaymentStatus.VOIDED, transition = SubscriptionTransition.LAPSE)})
```

`DECLINED` and `VOIDED` both select `LAPSE`. The payment machine keeps one definition for all three bindings, and its
definition does not change by being bound.

### Sub-machines over the same entity

A child can also govern a second state of the parent's own entity. With annotations, such a `Submachine` names none of
`child`, `parent` or `parentLookup`. In the DSL, `submachine(state, child)` takes no lookup, since the child entity is
the parent itself. In `SameEntitySubmachineTest`, one of the billing tests, a stay keeps its own status and its payment
status in two fields, and a payment graph over `Stay` governs the second one:

```java
/** Built once: the payment machine must be built from the very definition the stay binds. */
static final GraphDefinition<Stay, PaymentStatus, PaymentTransition> PAYMENT_GRAPH = StateGraph.define(STAY_PAYMENTS)
        .initial(PaymentStatus.AUTHORIZING)
        .terminal(PaymentStatus.CAPTURED)
        .terminal(PaymentStatus.DECLINED)
        .terminal(PaymentStatus.VOIDED)
        .statePolicy(PaymentStatus.AUTHORIZING, "an authorization is captured, declined or voided")
        .transition(PaymentTransition.CAPTURE, PaymentStatus.AUTHORIZING, PaymentStatus.CAPTURED).endTransition()
        .transition(PaymentTransition.DECLINE, PaymentStatus.AUTHORIZING, PaymentStatus.DECLINED).endTransition()
        .transition(PaymentTransition.VOID, PaymentStatus.AUTHORIZING, PaymentStatus.VOIDED).endTransition()
        .build();

static final GraphDefinition<Stay, StayStatus, StayTransition> STAY_GRAPH = StateGraph.define(STAYS)
        .initial(StayStatus.REQUESTED)
        .terminal(StayStatus.CONFIRMED)
        .terminal(StayStatus.CANCELLED)
        .statePolicy(StayStatus.REQUESTED, "a stay is booked first")
        .statePolicy(StayStatus.PAYING, "the payment decides")
        .transition(StayTransition.BOOK, StayStatus.REQUESTED, StayStatus.PAYING).endTransition()
        .transition(StayTransition.CONFIRM, StayStatus.PAYING, StayStatus.CONFIRMED).endTransition()
        .transition(StayTransition.CANCEL, StayStatus.PAYING, StayStatus.CANCELLED).endTransition()
        .submachine(StayStatus.PAYING, PAYMENT_GRAPH)
        .exit(PaymentStatus.CAPTURED, StayTransition.CONFIRM)
        .exit(PaymentStatus.DECLINED, StayTransition.CANCEL)
        .exit(PaymentStatus.VOIDED, StayTransition.CANCEL)
        .endSubmachine()
        .build();
```

`STAYS` and `STAY_PAYMENTS` are two keys over `Stay`, with different state enums, so one registry holds both machines,
each with the state accessors of its own field:

```java
private static MachineRegistry registry() {
    return MachineRegistry.builder()
            .add(StateMachines.from(STAY_GRAPH)
                    .state((Stay stay) -> stay.status, (Stay stay, StayStatus status) -> stay.status = status)
                    .identity((Stay stay) -> stay.id))
            .add(StateMachines.from(PAYMENT_GRAPH)
                    .state((Stay stay) -> stay.paymentStatus,
                            (Stay stay, PaymentStatus status) -> stay.paymentStatus = status)
                    .identity((Stay stay) -> stay.id))
            .build();
}
```

A capture through `registry.of(STAY_PAYMENTS)` moves the stay's payment status to `CAPTURED` and its own status to
`CONFIRMED` in one commit.

### Asynchronous exits

An exit waits under the deadline of the call that runs it: the budget of the machine whose call started the root, which
the exit edge's own timeout can only shorten. A machine whose calls can run an asynchronous edge implicitly, as an exit
or as an edge an exit leads to, therefore needs an `asyncTimeout` even when its own graph is synchronous. `holdRoom` is
asynchronous, so `Bookings` is an asynchronous machine, and so is `Payments`, since its `capture` runs `DEPOSIT_PAID`.
`Subscriptions` runs no asynchronous edge, directly or implicitly. The generated builder therefore takes the shared
timeout first and installs it on `Bookings` and `Payments` only. `asyncTimeout(Payments.class, Duration)` overrides the
payment machine's budget, and `asyncTimeout(Subscriptions.class, Duration)` throws `IllegalArgumentException`. Through
`billing.payments().async(...)`, the whole call runs on the payment machine's executor, the exit included, and the
binding is checked when the call starts running. [Deadline rules](#deadline-rules) has the whole rule, and the billing
test `AsyncExitTest` holds the exit open with a `ControlledAsyncAction`.

### Diagrams, concurrency and tests

`GraphvizExporter` draws each bound state as a cluster labelled with the state and the child machine, such as
`DEPOSIT: Payment/PaymentStatus`, which holds the child's own diagram.
[Inspect graphs and export diagrams](#inspect-graphs-and-export-diagrams) says how each edge is drawn, and the core
tests pin a booking with one payment machine bound in two states in
[submachine-shared-child.dot](../graphmaquina-core/src/test/resources/golden/submachine-shared-child.dot).

A call that ends a child its parent held commits the parent's exit, or moved the parent itself, so the commit's check
of the original states covers the parent. A call that moves a child to a state that is not terminal leaves the parent
out of the commit, so serialize the calls of a parent and its sub-machines as those of one aggregate, as
[initialize and protect state](#initialize-and-protect-state) explains.

Assert bindings, starts and both refusals with the typed views of the testing guide's
[sub-machines](testing.md#sub-machines) section. The
[billing tests](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/billing) run the flows of this
section: the starts and exits of a booking, the detached deposit of a cancelled booking, the `NO_ROOM` route, a renewal
that restarts its payment, the stay and the asynchronous exit.

## Commit, hooks and observers

![Root actions, cascades and sub-machines stage changes in a shared unit of work. Commit checks original states and
writes final states; hooks and independent watch reactions follow, then the observers learn how the call
ended, and the call returns its outcome with its frozen flow and its reactions.](images/commit-lifecycle.svg)

Observers are told that the call starts before step 1. A call that completes tells them in step 8; one that throws tells
them before the exception leaves the call.

The runtime's normal root execution follows this sequence:

1. Read the effective entity state and resolve the single edge keyed by `(state, requested identifier)`.
2. Evaluate guards and run actions, including any nested cascades, the starts of the sub-machines whose state an
   edge enters and the exits of those that end, against a shared unit of work.
3. Begin the commit: verify that participating entities still have their expected original states.
4. Write each participating entity's final state through its setter.
5. Release the root reentrancy marker and invalidate transition contexts.
6. Run hooks for the committed hops, in log order.
7. Dispatch watch reactions for moved peers.
8. Freeze the call's flow, notify the observers that the call completed, and return its outcome, which carries the
   frozen flow.

A rejected or ignored root does not commit state changes. A handled failure does commit its failure route.
External side effects performed by actions, and mutations to other entity fields, are not automatically undone
when the unit of work aborts. To set a field only once the call commits, an action can leave the value in the call's
flow and a hook can assign it, as [set a field only when the call commits](#set-a-field-only-when-the-call-commits)
shows. A call that [logs](#debug-a-call-logs-and-the-calls-flow) at `DEBUG` writes its `committed` and `started` lines
after step 5, and its `end` or `failed` line just before the observers learn how it ended. At `TRACE`, it also logs
the check of step 3, each setter of step 4 before it is called, each hook of step 6 before it runs and each write that
hook makes to the call's flow, and each owner lookup of step 7.

### After-commit hooks

Use a `@GraphHook` when code must run after GraphMaquina has written state. The quickstart prints every
committed hop from such a hook. The shop notifies its carrier after the shipping edge commits, and puts each
item it cancels back in stock once the item's `CANCEL` hop commits.

Hooks run synchronously on the operation's thread. Any throwable a hook throws that is not an `Error`, checked ones
included, becomes `AfterCommitException`: the state is already committed. Its `hook()` names the hook, `hop()` the
committed hop it ran for, `records()` every hop the call committed, `starts()` every sub-machine the call started,
and `flow()` the call's flow, with what the hooks wrote, the failing hook's writes before it threw included; a start
runs no hook. An `Error` propagates unchanged instead. Either
stops the remaining normal post-commit sequence, so do not assume later hooks or watch dispatch ran; the observers are
told the call failed. Make externally visible notifications idempotent and choose durable delivery separately if your
application needs it.

A hook sees the entity's committed final state. When a cascade stages multiple hops for one entity, the setter
writes its final destination once, while hooks still follow committed hop order.

For the endpoints, identifier, kind and terminality of that particular hop, and for the call's flow, add a
`CommitContext<S, T>` parameter, as the quickstart's hook does. The original entity-only signature remains supported:

```java
import io.github.graphmaquina.api.CommitContext;

@GraphHook
public void committed(Ticket ticket, CommitContext<TicketState, TicketTransition> commit) {
    System.out.println(commit.from() + " -" + commit.transition() + "-> " + commit.to()
            + " (" + commit.kind() + ")");
    if (commit.isTerminal()) {
        System.out.println("Workflow closed: " + ticket);
    }
}
```

`kind()` is `ACTION` or `MOVE`. `isTerminal()` describes the hop's destination and comes from
`Definition.terminals` (or the DSL's `terminal(...)` declarations). There is no need for a second terminal
flag on the state enum. In a multi-hop cascade, `commit.to()` can differ from the entity's final state. Hooks on
handled failure routes receive the recovering edge's context.

`flow()` is [the call's flow](#the-calls-flow), which every hook of the call shares: the hook reads what the steps of
the call put, and can put and remove values, which the later hooks of the call read. Nothing undoes those writes: the
call committed before its hooks run. The context can be retained, and its endpoints, identifier, kind and terminality
stay readable, but its flow works only while the hook runs and only on the thread running it: afterwards each operation
of the flow throws `StaleContextException`, and on another thread `WrongThreadException`. Once the hook returned or
threw, its flow no longer holds the call, so a retained context keeps none of the call's values in memory. Each hook
gets a handle of its own on the flow, so a later hook cannot use the one an earlier hook kept. A hook that takes the
entity alone does not see the flow. A hook can start a new call: that call has a flow of its own, and once it returns,
the hook's flow still reads and writes the flow of the call that ran the hook. That call is a new root, which
`maxWatchChain` never counts: the watch reactions it triggers begin a chain of their own. A hook whose call runs that
same hook again, directly or through other calls, therefore recurs until the thread's stack overflows, each call having
committed before its hooks ran, as [build and manage machine sets](#build-and-manage-machine-sets) says of a
`StackOverflowError`.

To call a contextual hook by hand, in a unit test, build its context with the five-argument constructor,
`new CommitContext<>(from, transition, to, kind, isTerminal)`. Its flow belongs to no call: it starts empty, has no
entity, works on any thread and never goes stale, so the test can put what the steps would have put, run the hook
and read back what it wrote.

The DSL takes the entity-only form through `afterCommit("committed", actions::committed)` and the contextual
one through `afterCommitWith("committed", (entity, commit) -> ...)`; an edge declares at most one hook. An
annotated context must use the machine's exact state and transition types. Other parameter shapes, non-void
returns and ambiguous symbols with both signatures marked are rejected.

Declare `afterCommit` on `Definition` to supply a default hook for every edge in that machine, whether it is
declared on a method or on the interface itself. An edge's explicit hook replaces the default; the two are
not composed, and there is no sentinel that switches the default off for one edge. Omitting the edge element,
or setting it to `{}`, inherits the default. `Definition.afterCommit` also accepts zero or one value;
multiple values are `SG607`.

The default applies to every committed hop, including explicit self-loops, failure routes, transitions
triggered by watches and the exits of sub-machines — they are all real edges. It never runs for a rejected
request, whatever its kind, an `Ignored`, an abort or a rollback. A failure route uses its own effective hook,
not the hook of the edge that failed. Hooks still run per committed hop, so one entity can reach the default
more than once in a cascade.

### Observers

An observer sees every call of the set: each operation and each `reevaluate`, watch reactions included. A cascade
is part of the call that runs it. With the ticket set:

```java
TicketMachines machines = TicketMachinesRuntime.builder(new TicketActions())
        .observer(new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                System.out.println(call + ": " + outcome.code() + " -> " + outcome.recordedState()
                        + " in " + elapsed.toMillis() + " ms");
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                System.out.println(call + ": failed in " + failure.phase() + ", states "
                        + failure.statesWritten() + ": " + failure.error());
            }
        })
        .build();
```

Each call notifies `onStarted` once, then exactly one of `onCompleted` and `onFailed`, on the thread that runs it,
before the operation returns or throws. `MachineCall` names the machine, the entity, the requested identifier (empty for
`reevaluate`) and whether a watch reaction made the call. Its `toString()` never prints the entity, and the same
instance reaches every event of the call. A reaction starts and ends inside the call that triggered it, so events nest
like a stack on their thread. `onFailed` receives the very throwable the caller gets, with the phase the call was in and
how far it got in writing states; an `Error` is reported too, then propagates. So an observer reads the call's flow as
the caller does: `outcome.flow()` in `onCompleted`, and the `flow()` of the `CallFailedException` in `onFailed`, which
the call attached before it told the observers.

An observer watches without taking part. A throwable that is not an `Error` is logged as a warning through
`System.getLogger("io.github.graphmaquina")` and changes neither the call nor the other observers; an `Error`
propagates. The warning is best effort: anything but an `Error` from its backend, a checked exception included, is
contained too, while an `Error` from it propagates unchanged. An `InterruptedException` that the observer or that
backend throws, once contained, interrupts the thread again. Either `Error`, the observer's own or its warning
backend's, voids for that call the promise of one `onStarted` and exactly one end event, and so does a
`StackOverflowError` or another `VirtualMachineError` that strikes again while the call tells its observers, as it
can when the call ended in one: telling them can need the stack or the memory that ran out, so they are told best
effort, and that `Error` propagates. That warning is the only record
logged on `io.github.graphmaquina` itself: the lines of each call go to its child logger
`io.github.graphmaquina.runtime`, as [debug a call](#debug-a-call-logs-and-the-calls-flow) describes. An observer cannot
start a transition on its own thread either: an operation called from an observer throws `ReentrantTransitionException`,
which is contained. One submitted through `async(...)` runs on the executor's thread as a call of its own, unless it
runs on the observer's own thread, through an executor that runs tasks inline or a wait that runs a `ForkJoinPool`'s
queued tasks, in which case the returned future fails with that exception as its cause. When a failure
must fail the call, write a hook, or a `Definition.afterCommit` default for every edge of a machine. Observers run on
the thread of every call, so keep them thread-safe and quick. Add as many as you need: they are notified in the order
they were added.

## Debug a call: logs and the call's flow

### Logs

The runtime logs each call through `System.getLogger("io.github.graphmaquina.runtime")`, standalone machines and
machine sets alike, at two levels. `DEBUG` logs how each call started, the hops and starts it committed and how it
ended; `TRACE` adds each step the call took on the way. Nothing is logged at `INFO` or above: the caller already
receives the outcome or the exception. The logger is off by default. `java.util.logging`, the JDK's default backend for
`System.Logger`, maps `DEBUG` to `FINE` and `TRACE` to `FINER`, and its default configuration logs neither.

To see the lines, lower the logger and a handler to `FINE`, for instance in a `logging.properties` file:

```properties
handlers = java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level = FINE
java.util.logging.SimpleFormatter.format = %4$s: %5$s%n
io.github.graphmaquina.runtime.level = FINE
```

Start the JVM with `-Djava.util.logging.config.file=logging.properties`. The console handler drops records below
`INFO` until its own level is lowered, so the logger's level alone shows nothing. The format line is optional: it
prints each record on one line. As a child of `io.github.graphmaquina`, the runtime logger takes that logger's level
when it has none of its own. For the `TRACE` lines too, write `FINER` in place of `FINE` on both levels: a handler left
at `FINE` drops them.

With this file, a ticket machine like the quickstart's, without its `committed` hook, called as its `App` begins
(submit and approve `T-1`, then submit `T-2` and `T-3`), logs these lines; the elapsed times vary from run to run:

```text
FINE: call=1 start Ticket/TicketState SUBMIT
FINE: call=1 committed Ticket/TicketState#T-1 OPEN -SUBMIT-> REVIEW depth=0
FINE: call=1 end code=APPLIED state=REVIEW entity=Ticket/TicketState#T-1 records=1 starts=0 reactions=0 aborted=0 elapsed=15033us
FINE: call=2 start Ticket/TicketState APPROVE
FINE: call=2 committed Ticket/TicketState#T-1 REVIEW -APPROVE-> DONE depth=0
FINE: call=2 end code=APPLIED state=DONE entity=Ticket/TicketState#T-1 records=1 starts=0 reactions=0 aborted=0 elapsed=322us
FINE: call=3 start Ticket/TicketState SUBMIT
FINE: call=3 end code=GUARD_REJECTED state=OPEN entity=Ticket/TicketState#T-2 records=0 starts=0 reactions=0 aborted=0 reason="a ticket needs a title" elapsed=658us
FINE: call=4 start Ticket/TicketState SUBMIT
FINE: call=4 committed Ticket/TicketState#T-3 OPEN -DECLINE-> FAILED depth=0
FINE: call=4 end code=FAILURE_ROUTED state=FAILED entity=Ticket/TicketState#T-3 records=1 starts=0 reactions=0 aborted=0 reason="the review board declined the ticket" elapsed=1060us
```

With both levels at `FINER`, the fourth of those calls, whose action fails into its route, logs these lines:

```text
FINE: call=4 start Ticket/TicketState SUBMIT
FINER: call=4 depth=0 resolved Ticket/TicketState#T-3 OPEN -SUBMIT-> REVIEW kind=ACTION
FINER: call=4 depth=0 guard hasTitle PASS elapsed=5us
FINER: call=4 depth=0 action register start mode=sync
FINER: call=4 depth=0 action register FAILED reason="the review board declined the ticket" elapsed=284us
FINER: call=4 depth=0 route SUBMIT -> DECLINE rollback-to=0 reason="the review board declined the ticket"
FINER: call=4 commit-check entities=1 hops=1
FINER: call=4 setter Ticket/TicketState#T-3 OPEN -> FAILED entries=1
FINE: call=4 committed Ticket/TicketState#T-3 OPEN -DECLINE-> FAILED depth=0
FINE: call=4 end code=FAILURE_ROUTED state=FAILED entity=Ticket/TicketState#T-3 records=1 starts=0 reactions=0 aborted=0 reason="the review board declined the ticket" elapsed=2644us
```

The quickstart's own machine also runs its `committed` hook for the `DECLINE` hop, so at `TRACE` it also logs a `hook`
line after its `committed` line.

SLF4J and Log4j receive the same lines through their `System.LoggerFinder` bridges on the class path, which map
`DEBUG` and `TRACE` to their own levels of the same name. Set the logger `io.github.graphmaquina.runtime` to `debug` or
`trace` there:

- SLF4J 2: add `org.slf4j:slf4j-jdk-platform-logging` next to `slf4j-api` and a binding. With `slf4j-simple`, pass
  `-Dorg.slf4j.simpleLogger.log.io.github.graphmaquina.runtime=trace`.
- Log4j 2: add `org.apache.logging.log4j:log4j-jpl` next to `log4j-api` and `log4j-core`. In a `log4j2.properties`
  file, `logger.graphmaquina.name = io.github.graphmaquina.runtime` and `logger.graphmaquina.level = trace`.

These two bridges were checked with SLF4J 2.0.20 and Log4j 2.26.1 on JDK 21.

Each message starts with `call=<id>`. The id numbers the calls that log, at either level, from 1, with one counter for
every machine. A `TRACE` line of a step names its depth next: 0 for the root's own step, one more for each cascade and
each exit of a sub-machine below it. The `TRACE` lines of the commit, the hooks and the watch dispatch name none.
Square brackets below mark the parts a line may leave out.

| Level | Line | Logged when |
| --- | --- | --- |
| `DEBUG` | `call=<id> start <machine> <request>[ trigger=<id>]` | A call starts. `<machine>` is the machine key, such as `Ticket/TicketState`, and `<request>` the identifier `execute` was asked for, or `reevaluate`. A watch reaction adds `trigger=` and the id of the call whose commit triggered it, if that call logged its own lines. |
| `DEBUG` | `call=<id> committed <ref> <from> -<T>-> <to> depth=<d>` | The call committed a hop: one line per hop, cascaded hops and the exits of sub-machines included, at the depth of the step that took it. |
| `DEBUG` | `call=<id> started <ref> <previous> -> <initial> parent=<ref> node=<node> depth=<d>` | The call committed the start of a sub-machine: the child moved from `<previous>` to its initial state when its parent entered `<node>`. |
| `DEBUG` | `call=<id> end code=<code> state=<state>[ entity=<ref>] records=<n> starts=<n> reactions=<n> aborted=<n>[ reason="<reason>"] elapsed=<us>` | The call ended with its outcome, just before its [observers](#observers) hear of it: its `code()` and `recordedState()`, the sizes of its `records()`, `starts()` and `reactions()`, how many of those reactions were aborted, its `explanation()` when it has one, as given and unescaped, and the time since the call started, in whole microseconds. An `Error` that an observer then throws reaches the caller in place of the outcome. |
| `DEBUG` | `call=<id> failed phase=<phase> written=<written>[ entity=<ref>] error=<error> elapsed=<us>` | The call ended with its `CallFailedException`, just before its [observers](#observers) hear of it: its `phase()` and `statesWritten()`, the exception's class name and message, without a stack trace, and the time since the call started. An `Error` that an observer then throws reaches the caller in place of the exception. |
| `TRACE` | `call=<id> depth=<d> resolved <ref> <from> -<T>-> <to> kind=<kind>[ exit-of=<ref>]` | A step resolved the single edge keyed by its entity's state and the requested identifier, before its guards run. `<kind>` is the edge's `kind()`, `ACTION` or `MOVE`. The step of a sub-machine's exit adds `exit-of=` and the child whose terminal state selected the exit. |
| `TRACE` | `call=<id> depth=<d> no-edge <ref> <from> <T>` | A step found no edge for its entity's state and the requested identifier: the request is rejected, or, inside a cascade or an exit, the call aborts. |
| `TRACE` | `call=<id> depth=<d> binding <ref> parent=<ref> node=<node>` | The step of a sub-machine's child found the parent that holds it in `<node>`, before it looked up its edge. |
| `TRACE` | `call=<id> depth=<d> detached <ref> reason="<reason>"` | The step of a sub-machine's child found parents, but none that holds it: the request is rejected as `DETACHED`, for `<reason>`, or, inside a cascade or an exit, the call aborts. |
| `TRACE` | `call=<id> depth=<d> guard <name> PASS elapsed=<us>` / `call=<id> depth=<d> guard <name> REJECT reason="<reason>" elapsed=<us>` | A guard passed, or rejected the request for `<reason>`, as given and unescaped, with the time it took. A guard that throws logs neither. |
| `TRACE` | `call=<id> depth=<d> action <name> start mode=<sync\|async>` | An action starts, synchronous or asynchronous: the edge's own action, or the recovery action of a failure route. |
| `TRACE` | `call=<id> depth=<d> action <name> awaiting budget=<us\|none>` | An asynchronous action returned its stage, and the step waits for it at most `<us>`: what is left of the edge's `timeout` or of the call's `asyncTimeout`, whichever ends first. `none` when neither sets a limit. |
| `TRACE` | `call=<id> depth=<d> action <name> OK elapsed=<us>` / `call=<id> depth=<d> action <name> FAILED[ kind=<kind>] reason="<reason>" elapsed=<us>` / `call=<id> depth=<d> action <name> THREW error=<class> elapsed=<us>` | The action ended, after the time since its `start` line. `OK`: it returned `ActionOutcome.ok()`. `FAILED`: it returned a failure, with the simple name of its failure kind when it has one, and its reason. `THREW`: it threw or completed its stage exceptionally, or the step gave up on it, for a null result or a wait that timed out, was interrupted or was refused. The line names the class of that throwable, never its message. An `Error` the action throws logs no end. |
| `TRACE` | `call=<id> depth=<d> route <failed T> -> <recovery T> rollback-to=<savepoint>[ kind=<kind>] reason="<reason>"` | The failure of `<failed T>` took its route: the step dropped the hops and starts the call took after the first `<savepoint>` of them, with the writes to the call's flow made since, and takes `<recovery T>`. The line names the failure kind the route reports, if any, and the failure's reason; for a routed throwable, that is the reason composed from it, as [recover by failure kind and exception type](#recover-by-failure-kind-and-exception-type) says, which the outcome's `explanation()` leaves out. |
| `TRACE` | `call=<id> depth=<d> unhandled <failed T> reason="<reason>"` | The failure of `<failed T>` has no route: the step dropped the hops and starts it took and its writes to the call's flow, and the call throws `UnhandledFailureException`, or, inside a cascade or an exit, aborts. |
| `TRACE` | `call=<id> depth=<d> cascade <ref> <T>[ policy=<policy>]` | An action cascades `<T>` to the peer `<ref>`, at the action's depth; each element of `cascadeAll` adds its `CascadePolicy`. The lines of the cascaded step follow, one depth deeper. |
| `TRACE` | `call=<id> depth=<d> cascade-result <ref> <T> code=<code>` | The cascaded step returned its outcome, with its `code()`. |
| `TRACE` | `call=<id> depth=<d> collect-rollback <ref> <T> error=<error>` | Under `COLLECT`, the element `<ref>` aborted with `<error>`, in its step or, for a cycle or the depth limit, before its step could run, and was rolled back. The call stays poisoned while the line is written; then the poison the element left is cleared and the next element runs. An identity whose `equals` or `hashCode` throws during that rollback leaves no such line: the abort of the cascading step that carries what it threw replaces the element's abort as the poison, with a `poison` line, and the cascade stops. |
| `TRACE` | `call=<id> depth=<d> submachine-start <ref> <previous> -> <initial> parent=<ref> node=<node>` | A hop entered `<node>`, which starts its sub-machine: the child moves from `<previous>` to its initial state, at the depth of the entering step. |
| `TRACE` | `call=<id> depth=<d> submachine-exit <ref> terminal=<state> parent=<ref> exit=<T>` | The child `<ref>` stands in `<state>`, a terminal state of its graph, which selects the exit `<T>` of its parent. The step of the exit follows, one depth deeper. |
| `TRACE` | `call=<id> depth=<d> poison error=<error>` | A failure poisoned the call, which then commits nothing: the abort of a step at a depth above 0, a cascade that failed before its step could run, such as a cycle or a machine the registry does not hold, or the aggregate of a `COLLECT`. A failure that finds the call already poisoned logs no line, since the call keeps its first poison. `COLLECT` clears the poison of each element it rolls back, so one call can log the poison of several elements, then that of the aggregate; it clears it too when an identity's `equals` or `hashCode` throws while it rolls an element back, and logs the poison of the abort that carries what it threw. An `Error` that leaves a cascade poisons the call without a line. A step's own abort logs its line after the lines the step logged before it aborted, its action's end line included, whether the action threw or the step gave up on it: a `poison` line between an action's `start` and end lines comes from a cascade of that action. |
| `TRACE` | `call=<id> depth=<d> flow-put <key>[=<value>]` | An action put a value in [the call's flow](#the-calls-flow), at the depth of its step. `<key>` is the key as `name:Type`, with the simple name of its class; a key made with `FlowKey.visible` adds `=` and the value's `toString()`. |
| `TRACE` | `call=<id> depth=<d> flow-remove <key>` | An action removed the value of `<key>` from the call's flow. |
| `TRACE` | `call=<id> commit-check entities=<n> hops=<n>` / `call=<id> commit-conflict reason="<conflict>"` | The commit checks the original state of each entity the call's hops and starts moved: `entities=` counts them, and `hops=` the hops the call took, starts not counted. A state that changed while the call ran is a conflict: the commit writes nothing and throws `CommitFailedException`. |
| `TRACE` | `call=<id> setter <ref> <from> -> <to> entries=<n>` | The commit is about to call the setter of `<ref>`, which moves it from its first state to its last, through `<n>` hops and starts the call took. |
| `TRACE` | `call=<id> hook <hook> <ref> <T>` | The hook `<hook>` is about to run for the committed hop `<T>` of `<ref>`. |
| `TRACE` | `call=<id> hook-flow-put <hook> <key>[=<value>]` | The hook `<hook>` put a value in [the call's flow](#the-calls-flow). `<key>` and `<value>` print as in `flow-put`. |
| `TRACE` | `call=<id> hook-flow-remove <hook> <key>` | The hook `<hook>` removed the value of `<key>` from the call's flow. |
| `TRACE` | `call=<id> watch watcher=<machine> moved=<ref> owner=<ref\|none>` / `call=<id> watch watcher=<machine> moved=<ref> owner-state=<state>` / `call=<id> watch-lookup-threw watcher=<machine> moved=<ref> error=<error>` | The watch of `<machine>` looked up the owner of the entity the call moved: the owner it found in the watched state, which a reaction reevaluates, or `none` when it found no owner; `owner-state=` and the state of an owner it found in another state, which no reaction reevaluates; or the lookup threw `<error>`, which aborts that reaction. |

The `committed` and `started` lines follow the order the call took its hops and starts, and come after the commit,
before the hooks: a call whose commit fails logs none. A watch reaction is a call of its own, whose lines come between
the `committed` lines and the `end` line of the call that triggered it. A cascade is part of the call that runs it: it
logs no `start`, `end` or `failed` line of its own, and its `TRACE` lines carry the id of that call. A request refused
before it starts, as [observers](#observers) see it, logs nothing. Each call logs on the thread that runs it: one
submitted through `async()` logs on the executor's thread, and the submission itself logs nothing.

At `TRACE`, a step logs as it goes: its binding check, its edge, its guards, the start of the sub-machine its hop
enters, its action and, in the order the action makes them, its writes to the flow and its cascades, then its failure
route, if any, and last the exits it runs. The lines of the commit come before the `committed` lines; the hook and
watch lines come after them, before the lines of the reactions.

An entity appears in a line only as `<ref>`: its machine key and its identity, such as `Ticket/TicketState#T-1`. A
standalone machine without `identity(...)` prints `#@` and the entity's identity hash code in hexadecimal instead.
The runtime prints the identity it has already computed, so a line never calls the identity function, a getter or
the entity's `toString()`, unless your code puts the entity in the flow under a visible key: a `flow-put` or
`hook-flow-put` line of a key made with `FlowKey.visible` prints the value's `toString()`, whatever the value is. The
`start` line names no entity, and `end` and `failed` name it only once the call computed it, which its step does as it
begins: a reevaluation that selected no edge names none, and so does one that failed before its step began, while it
read the state, ran its watch or exceeded the watch chain. A reevaluation whose step began names it, even when the step
failed before its edge ran, in its binding check for instance. The identity may be personal data, such as an e-mail
address used as an identifier, and the reasons, exception messages and visible flow values the lines print may carry
internal details; treat these logs like any record that carries your identifiers. If the `toString()` of an identity,
of an exception or of a visible flow value throws anything but an `Error`, a checked exception included, the line prints
`<toString threw <exception class>>` in its place, after the machine key and its `#` for an identity, and the call
goes on, with the thread interrupted again when what it threw is an `InterruptedException`; an `Error` propagates. A
`toString()` that returns `null` prints `<toString returned null>` for an
exception or a visible flow value, and `null` after the `#` of an identity.

The runtime asks the logger for its two levels once, when a call starts, and builds no line of a level that is off: a
level raised while a call runs applies from the next call, a call logged at `DEBUG` alone builds no `TRACE` line, and a
call that logs nothing pays for no line. Anything but an `Error` from the logging backend, a checked exception included,
loses the line and changes nothing in the call, except that an `InterruptedException` interrupts the thread again; an
`Error` propagates. An `Error` on a `committed`, `started`, `end` or
`failed` line ends the call with that `Error`, which the [observers](#observers) receive as the call's end event, in the
phase the call reached: the `committed` and `started` lines come once every setter returned, in `HOOK`. An `Error` that
ends a call is not a `CallFailedException` and gets no `failed` line: the lines logged before it stay, and none follows
them.

### The call's flow

Each call carries one flow: typed values that its steps and its post-commit hooks share, and the entity the call was
made for. When the root machine of the call was built with `recordTrail()`, the flow also lists what the call took,
each at the instant it took it, as [record the call's trail](#record-the-calls-trail) describes. It lives in memory for
that call alone: nothing of it is persisted, and the next call starts with an empty one. When the call ends, its
caller receives a frozen copy, as [read the flow after the call](#read-the-flow-after-the-call) shows. Every step of
the call reaches the same flow, whatever entity the step moves: the root's own step, each cascaded step and the exit
of each sub-machine, and so does the hook of every hop the call commits. A watch reaction is a call of its own, with
its own flow, and so is a call that a hook starts.

![The shop cancels an order of two items on a set that records its trail. The order's step puts the batch in the
flow and cascades CANCEL to the items, whose steps put their restocks; each write comes after its step's hop, and
the trail lists each hop with its instant. The commit writes the states, the hooks apply the restocks, and the
caller receives the flow frozen in the outcome. A failure route drops what its step took and wrote, while earlier
writes stay. When an item is PACKED, its element aborts and is dropped, the cascade aborts the call, nothing
commits, and the failure's flow keeps the other writes and ends its trail with Failed.](images/call-flow.svg)

An action reads and writes the flow through `ctx.flow()`, a `FlowContext`. A guard reads it through `ctx.flow()` too,
and gets a `FlowView`, without `put` or `remove`. A hook that takes a `CommitContext` reads and writes it through
`commit.flow()`, a `FlowContext`, once the call committed; a hook that takes the entity alone does not see it. A value
lives under a `FlowKey`, a name and a class: two keys with the same name and class read and write the same value.
Declare each key once, where every step that uses it can see it, as the shop does in
[ShopFlow.java](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/ShopFlow.java):

```java
public final class ShopFlow {

    public static final FlowKey<String> CANCELLATION = FlowKey.visible("cancellation", String.class);

    private ShopFlow() { }

    public static FlowKey<Restock> restockOf(Item item) {
        return FlowKey.of("restock:" + item.id(), Restock.class);
    }
}
```

`restockOf` gives each item a key of its own, named after the item's identifier: every step of the call shares the
flow, so the restocks of two items under one key would replace each other. `Restock` is a record of the shop,
`Restock(String batch, Order order)`.

`FlowKey.of` and `FlowKey.visible` refuse a null or blank name and a primitive class token: write `Integer.class` for
`int`. A key takes a class token, as `input(Class)` does, so it cannot name a generic type such as `List<String>`; use
the raw class, `List.class`. `put` refuses a null key or value with `NullPointerException`, and a value that is not an
instance of the key's class with `ClassCastException`; `get` and `remove` refuse a null key. `get` answers the value of
the last write of the key, and `keys()` lists the keys that hold a value, in the order of the write that gave each its
current value.

A cascaded step and the exit of a sub-machine receive no `input()`. When they need a value the caller passed, the
action that received it copies it into the flow before it cascades. The shop's `Orders` facade declares
`cancel(Order order, String cancellationId)`, whose identifier names the batch the order's items go back to stock in.
The order's `cancel` action copies it into the flow for each item it cancels. The operation refuses a null
identifier before the call starts, so the action always finds one:

```java
@GraphAction
public ActionOutcome cancel(TransitionContext<Order, OrderStatus, OrderTransition> ctx) {
    ctx.flow().put(ShopFlow.CANCELLATION, ctx.input(String.class).orElseThrow());
    ctx.cascadeAll(Items.class, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.COLLECT);
    return ActionOutcome.ok();
}
```

The item's `CANCEL` edge runs `restock`, which reads it. `ctx.entity()` is the item the step moves, and
`flow().entity()` the entity the root call was made for. Only the order's `cancel` cascades `CANCEL` to the items, so
the root is the order, and `flow().entity(Order.class)` casts it. That cast throws `ClassCastException` when the root
is of another class, and refuses a null or primitive class token as `input(Class)` does. `restock` leaves the item's
restock in the flow and changes no entity, for the reason the next paragraphs give:

```java
@GraphAction
public ActionOutcome restock(TransitionContext<Item, ItemStatus, ItemTransition> ctx) {
    String batch = ctx.flow().get(ShopFlow.CANCELLATION).orElseThrow();
    Order order = ctx.flow().entity(Order.class).orElseThrow();
    ctx.flow().put(ShopFlow.restockOf(ctx.entity()), new Restock(batch, order));
    return ActionOutcome.ok();
}
```

An action's write is undone with the step that made it. When a step fails with a failure its edge expects, one its
action returns or a throwable of a type the edge declares, the call drops every write made since that step took its hop,
its action's and those of the steps it cascaded to, and keeps what earlier steps wrote: the recovery action of its route
then writes anew, and without a route the step aborts with `UnhandledFailureException`. A `COLLECT` element whose abort
the cascade collects is dropped the same way, its writes with it, before the next element runs, unless an identity's
`equals` or `hashCode` throws while it is dropped, which stops the rollback and leaves what it had not undone. Once a
failed cascade poisoned the call, a step whose failure its edge expects is not dropped: it ends in the failure that
poisoned the call and keeps its writes, unless a `COLLECT` collects that failure and drops the whole element. No other
abort undoes a write: a call that aborts commits nothing, and the flow its failure hands out keeps what its steps wrote.
A hook writes after the commit, and nothing undoes its writes.

Values are stored by reference, never copied, and a rollback drops the write, not what was done to the object. An
action that puts a list, adds an element to it and then fails into its route leaves the recovery action without a
value under that key, while the list itself still holds the element. Treat stored values as immutable. Entities
behave the same way: what a step changes on its own entity through `ctx.entity()`, or on the root's through
`flow().entity(...)`, takes effect at once and is never undone, as
[commit, hooks and observers](#commit-hooks-and-observers) says of other entity fields. That is why the shop's
`restock` changes neither the item nor the order: its `COLLECT` cascade tries every item, then aborts the call if the
step of one of them aborted, and an item would then keep the batch of a cancellation that never committed. It
leaves the restock in the flow, and a hook applies it once the call commits, as
[set a field only when the call commits](#set-a-field-only-when-the-call-commits) shows.

The flow follows the rules of [contexts](#contexts-and-completion-callbacks). `ctx.flow()` and every operation of the
flow it returns, reads included, work only on the thread running the action or the guard, and only until the root
commits or aborts: elsewhere they throw `WrongThreadException`, afterwards `StaleContextException`. An asynchronous
action writes before it returns its stage. A write inside a callback of that stage, which may run on the service's
thread, throws `WrongThreadException` and stores nothing; the exception completes the stage exceptionally, so the call
aborts with it as its cause, unless a type the edge declares accepts it. The flow a hook receives works only on the
thread running the hook and only while it runs, as [after-commit hooks](#after-commit-hooks) describes.

Each write of an action is logged at `TRACE` as a `flow-put` or `flow-remove` line, and each write of a hook as a
`hook-flow-put` or `hook-flow-remove` line, which names the hook. Each prints the key as `name:Type`. A put with a
key made with `FlowKey.visible` also prints `=` and the value's `toString()`, whatever object it is: make a key visible
only for values you accept to see in your logs. A remove prints no value.

#### Set a field only when the call commits

What a step sets on an entity stays even when the call fails. To set a field only once its hop commits, the action
leaves the value in the flow, which a failure of its step undoes, and a post-commit hook assigns it. The shop's item
`CANCEL` edge declares `restock` as its action and `applyRestock`, another method of `ItemActions`, as its
`afterCommit` hook, which applies the restock that the item's step left:

```java
@GraphHook
public void applyRestock(Item item, CommitContext<ItemStatus, ItemTransition> commit) {
    Restock restock = commit.flow().get(ShopFlow.restockOf(item)).orElseThrow();
    item.restockedIn(restock.batch());
    restock.order().restocked(item);
}
```

Hooks run in the order of the committed hops, so the order lists its items in the order the cascade cancelled them.
When the step of an item aborts, for instance because the item is `PACKED` and has no `CANCEL` edge, the `COLLECT`
cascade aborts the call once it tried every item: nothing commits, no hook runs, and no item gets a batch, although
the flow the failure hands out holds the restock of every item whose step completed.
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java) asserts the
cancellation that commits and the one that aborts. When an action fails after its put and its step takes a failure
route, the route drops the write, and the hook that runs for the recovering hop finds nothing. Had `restock` set the
batch on the item itself, the item would keep it in both cases.

A hook can also be a machine's default, declared once with `afterCommit` on `Definition`: it then runs for every hop
of the machine that commits, the hops of failure routes included. The DSL has no default hook; declare it on each
edge with `afterCommitWith`. An edge that declares its own hook replaces the default, as
[after-commit hooks](#after-commit-hooks) says, so that hook must assign the value too. Like any hook, it does not run
when an earlier hook of the call throws. The flow belongs to the call, not to the hop, so every hook of the call reads
the same values: that is why the shop gives each item's restock a key of its own, which that item's hook reads.

#### Read the flow after the call

Once its hooks and its watch reactions ran, the call freezes its flow and hands it to its caller: `flow()` on the
outcome it returns, and `flow()` on the `CallFailedException` it throws. Both are a `FlowView`, read with `get`,
`keys()`, `entity()` and `trail()` as a guard reads it. For instance, a REST handler that cancels an order can answer
with the cancellation the call recorded, whether the call returned or failed:

```java
static Optional<String> cancellation(Orders orders, Order order, String cancellationId) {
    try {
        return orders.cancel(order, cancellationId).flow().get(ShopFlow.CANCELLATION);
    } catch (CallFailedException failure) {
        return failure.flow().get(ShopFlow.CANCELLATION);
    }
}
```

The frozen flow is a copy that the caller may keep. It never changes and has no owner thread, so any thread can read
it, such as a JSON serializer's after the handler returned. It compares by value: two outcomes are equal only when
their flows hold equal values, an equal entity and equal trails. Its values are the very objects the steps put, never
copies, and its entity is the live one, so a change made to them later shows through it: treat stored values as
immutable. Nothing reaches the call's flow any more: the handles of its steps and hooks are stale once the call ended.

- An applied or failed outcome holds the values present once the hooks ran. After a failure route, they lack what the
  failed step wrote and hold what its recovery action wrote.
- Reading never creates the flow; a write does, a `put` or a `remove` of a step or a hook, and so does the start of a
  call that records its trail. Until the call's first write, `get` answers empty and `keys()` an empty set, while
  `entity()` already answers the root entity. A call that records no trail and whose steps and hooks wrote nothing
  hands out `FlowView.empty()`, without values and without the entity, which `entity()` of the outcome holds, so two
  such calls hand out equal flows. Without the trail, a rejected outcome is always `FlowView.empty()`: only its guards
  ran, and a guard cannot write. An `Ignored` outcome and the outcome a cascade returns to the action that ran it
  answer `FlowView.empty()` too, whatever the machine records: a cascade's flow belongs to its root call.
- A watch reaction is a call of its own: the outcome in `Reaction.Done` hands out the reaction's flow, and the
  failure of an aborted reaction its own.
- A failure holds what the call wrote up to it, as [handle a failed call](#handle-a-failed-call) details.

#### Record the call's trail

A machine records the trail of the calls it starts when its builder, `StateMachines.Builder`, is given
`recordTrail()`. For instance, a registry whose order machine records it:

```java
static MachineRegistry shop(GraphDefinition<Order, OrderStatus, OrderTransition> orderGraph,
                            GraphDefinition<Item, ItemStatus, ItemTransition> itemGraph) {
    return MachineRegistry.builder()
            .add(StateMachines.from(orderGraph).state(Order::status, Order::setStatus).identity(Order::id)
                    .recordTrail())
            .add(StateMachines.from(itemGraph).state(Item::status, Item::setStatus).identity(Item::id))
            .build();
}
```

The machine of the root call decides for the whole call, as its `asyncTimeout` bounds the whole call. Cancelling an
order through this registry records the order's hop and the hop of each item its `cancel` action cascades to,
although the item machine was built without `recordTrail`, while a call started on an item records nothing, whatever
its steps reach. A watch reaction is a call of its own: its machine decides its trail, which `Reaction.Done` hands out
with the reaction's outcome, never in the trail of the call that triggered it.

The generated builder of a [machine set](#build-and-manage-machine-sets) offers `recordTrail()` and
`recordTrail(InstantSource clock)` as well, and hands them to the `StateMachines.Builder` of every machine of the set.
Since the root machine decides and every machine of the set records, every call made through the set records its
trail, the peers it cascades to and the exits of sub-machines its steps run included, and so does each watch reaction,
whose machine is one of the set. The set has no variant for one of its machines: a registry built with
`StateMachines.Builder`, as above, chooses machine by machine. The shop's
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java) builds its
set with `recordTrail()`, and reads in the trail of a cancellation the order's hop, then the hop of each item.

`flow().trail()` lists `FlowEntry` values, in the order the call took them, cascaded steps, the exits of sub-machines
and sub-machine starts included:

| Entry | When | What it holds |
| --- | --- | --- |
| `FlowEntry.Hop` | Each hop the call took | `hop()`, the hop's `TransitionRecord`, which in an outcome, and in an `AfterCommitException`, is the very instance its `records()` lists. On the hop of a failure route, `recovery()` holds what failed: the identifier of the transition whose step failed, the reason, the throwable if one failed the step, and the failure kind the outcome reports, if any: the one a returned failure named, or the one of the recovery rule whose type routed the throwable. |
| `FlowEntry.Start` | Each sub-machine the call started | `start()`, the start's `SubmachineStart`, which in an outcome, and in an `AfterCommitException`, is the very instance its `starts()` lists. |
| `FlowEntry.Rejected` | Last, when the call returns `Outcome.Rejected` | The root call's machine key and entity, the state, the identifier the caller asked for, or the one the watch selected in a reevaluation, whose call requests none, the `RejectionKind`, the reason and the guard that rejected, if one did. |
| `FlowEntry.Failed` | Last, when the call throws a `CallFailedException`, unless the clock throws as the call reads its instant | `failure()`, a `CallFailure` equal to the one the observers receive: the phase, `statesWritten()`, which says what the call wrote, and the exception. |

Each entry's `at()` is read on the root machine's clock. A hop's or a start's is read right after the call took
it, after the guards of its step and before its action runs, so an action reads its own hop in the trail. A
`Rejected` or `Failed` entry's is read when the call ended. For instance, a handler can turn the trail into lines for
its log or its response:

```java
static List<String> timeline(FlowView flow) {
    List<String> lines = new ArrayList<>();
    for (FlowEntry entry : flow.trail()) {
        String what = switch (entry) {
            case FlowEntry.Hop hop -> hop.hop().entity() + " " + hop.hop().from() + " -"
                    + hop.hop().transition() + "-> " + hop.hop().to()
                    + hop.recovery().map((FlowEntry.Recovery recovery) ->
                            " after " + recovery.failed() + " failed: " + recovery.reason()).orElse("");
            case FlowEntry.Start start -> start.start().child() + " started in " + start.start().initial();
            case FlowEntry.Rejected rejected -> "rejected " + rejected.transition() + ": " + rejected.reason();
            case FlowEntry.Failed failed -> "failed in " + failed.failure().phase() + ": "
                    + failed.failure().error().getMessage();
        };
        lines.add(entry.at() + " " + what);
    }
    return lines;
}
```

The trail follows the rollbacks of the call:

- A failure route drops the hop of the step that failed, with the hops and starts of the steps it cascaded to, and
  its recovery hop carries the `Recovery`. A `COLLECT` element whose step aborts leaves no entry either.
- A failed call keeps what it took up to the failure, then its `Failed` entry, unless the clock threw as the call
  read that entry's instant, as below. An abort undoes nothing, but the engine rolls back the step of an
  `UnhandledFailureException` first, so its trail lacks that step's entries.
- A `reevaluate` that fails before it runs an edge has the `Failed` entry alone, or no entry when the clock threw,
  beside the entity and no value.
- An `Error` ends the call without an entry and without a flow.
- An `Ignored` outcome and the outcome a cascade returns have no trail: both answer `FlowView.empty()`.

A step reads `ctx.flow().trail()` and a hook `commit.flow().trail()`, under the thread and lifetime checks of the
rest of the flow: a step reads what the call took so far, and a hook every hop and start the call committed. Each read
builds a new list, which does not follow later entries.

A call that records its trail creates its flow when its step starts, so it hands out a flow with the root entity and
the trail even when no step or hook wrote to it, a rejected call included. Two such outcomes are equal only when their
trails are, `at()` included.

`recordTrail()` reads `InstantSource.system()`. `recordTrail(InstantSource clock)` reads `clock` instead, on the thread
running the call, once per entry the call appends, an entry that a rollback later drops included: a test can pass
`InstantSource.fixed(...)`, a `Clock`, or a clock of its own that advances on each read, which then leaves a gap for
each dropped entry, such as the failed hop of a failure route. A clock that throws anything but an `Error`, a checked
exception included, fails the call, as an identity function that throws does: the entry it could not mark stays out of
the trail. When it throws while the call reads the instant of the `Failed` entry that ends a failed call, whatever
failed the call, the call's `CallFailedException` carries what it threw as suppressed, unless it threw that very
exception, and its trail ends without that entry; an `InterruptedException` suppressed there interrupts the thread
again. A clock that answers `null` counts as one that throws a `NullPointerException`. A call whose root machine was
built without `recordTrail` reads no clock and stores no time.

The trail records what the call took and how it ended; the [`TRACE` lines](#logs) show each step on the way, its
guards and its action included. To assert the flow and its trail in a test, see
[the call's flow and its trail](testing.md#the-calls-flow-and-its-trail) in the testing guide.

## Concurrency, identity and persistence

### Initialize and protect state

The entity owns its state. Initialize it in the constructor or field declaration, and persist it with your normal entity
mapping. A null root state ends the call in a `TransitionAbortedException` of phase `RESOLVE` whose cause is an
`UninitializedStateException`, and a cascaded peer's in one of phase `ACTION`, as does a sub-machine's, read when its
parent enters the bound state. A parent's, read to check the binding of a sub-machine, ends the call in one of phase
`RESOLVE` when the sub-machine is the root, and in one of phase `ACTION` inside a cascade or an exit. The runtime does not
silently replace a null state with the graph's initial state.

During a transition, only the runtime should call the state setter. It checks state again before commit to
catch action code or outside code that changed it unexpectedly. This check is not a lock: your application
must serialize conflicting operations, or provide an appropriate database locking/versioning strategy.
A sub-machine shows where the check stops. A call that ends a child its parent held commits the parent's exit, or
moved the parent itself, so the parent is in the commit, and the check catches a parent that another call moved
meanwhile. A call that moves the child to a
state that is not terminal leaves the parent out of the commit, and nothing notices that the parent moved.
Serialize the calls of a parent and its sub-machines as those of one aggregate.

The runtime does not roll back a setter that already ran if a subsequent setter throws.
`CommitFailedException` reports the entities whose setter returned (`applied()`), the one whose setter threw
(`failedEntity()`), which may or may not have been written, and those never tried (`pending()`); its
`statesWritten()` is `PARTIAL`, or `NONE` when the commit stopped before its first setter: the pre-commit check
refused it, or reading the states or grouping the log by entity threw. Keep state setters simple and total for valid
states; avoid remote calls, persistence flushes and unrelated business logic inside them.

### Stable identity

An identity is scoped by the machine key, which contains the entity, state and transition types. It must be
stable, non-null and suitable for identifying one domain entity. Using two different object instances with
the same machine identity in one unit of work is an `AmbiguousIdentityException`; this prevents silently
treating two representations of one row as separate entities.

When working with an ORM, load and reuse a consistent object instance for each identity within the operation.
Identity tracking does not provide database uniqueness enforcement or cross-process coordination.

### Framework and database boundaries

Create the set once at the lifecycle scope appropriate for your application and inject its action hosts with
existing repositories and services. Retrieve entities, invoke the facade, inspect the outcome and persist using
your application's transaction boundary.

GraphMaquina's after-commit means its in-memory state commit. It does not mean a surrounding database
transaction committed. If a message must follow a durable database commit, use your persistence framework's
transaction-completion facility or a transactional outbox rather than assuming the graph hook provides that
guarantee.

Executor-based calls do not automatically propagate thread-local transactions, security contexts or ORM
sessions. Arrange those boundaries on the execution thread. Async action callbacks must not use an entity or
persistence context concurrently without the application's own coordination.

A single `Definition.afterCommit` declaration covers every committed hop of the machine:

```java
@GenerateGraphApi
@DocumentsGraph.Definition(initial = DRAFT, terminals = ARCHIVED,
        state = DocumentRefs.Status.class, id = DocumentRefs.Id.class,
        afterCommit = DocumentPersistenceRefs.Save.class)
@DocumentsGraph.StatePolicy(state = DRAFT, otherwise = "publish before archiving")
@DocumentsGraph.StatePolicy(state = PUBLISHED, otherwise = "archive next")
public interface Documents extends Machine<Document, DocumentStatus, DocumentChange> {
    @DocumentsGraph.Transition(id = DocumentChange.PUBLISH, from = DRAFT, to = PUBLISHED)
    Outcome<Document, DocumentStatus, DocumentChange> publish(Document document);

    @DocumentsGraph.Transition(id = DocumentChange.ARCHIVE, from = PUBLISHED, to = ARCHIVED)
    Outcome<Document, DocumentStatus, DocumentChange> archive(Document document);
}

public final class DocumentPersistence {
    private final java.util.function.Consumer<Document> save;

    public DocumentPersistence(java.util.function.Consumer<Document> save) {
        this.save = java.util.Objects.requireNonNull(save);
    }

    @GraphHook
    public void save(Document document) {
        save.accept(document);
    }
}
```

`Documents`, `Document`, `DocumentStatus`, `DocumentChange` and `DocumentPersistence` are public types and
belong in separate files, each with the usual annotation and API imports and `import static ...DocumentStatus.*`
for the state constants; `DocumentChange` is the enum `{ PUBLISH, ARCHIVE }`. `Document` exports `id` and
`status` the same way described under Properties and accessors.

Assemble `Documents` into a `@StateGraphs` set as the quickstart shows, and inject `DocumentPersistence` through
that set's generated builder, providing your repository's save operation. If an edge declares a replacement hook,
that hook must call the shared persistence method too when the application requires every transition to be saved.
This hook runs after GraphMaquina's in-memory commit; the application's database transaction still controls
durable commit and rollback.

## Inspect graphs and export diagrams

Every facade exposes its definition:

```java
GraphDefinition<Ticket, TicketState, TicketTransition> graph = tickets.definition();
System.out.println(graph);
System.out.println(tickets.transitionsFrom(TicketState.REVIEW));
System.out.println(graph.outgoingFrom(TicketState.REVIEW));
System.out.println(graph.findTransition(TicketState.REVIEW, TicketTransition.APPROVE));
System.out.println(graph.policy(TicketState.OPEN).reasonFor(TicketTransition.APPROVE));
```

`toString()` is a short summary of the machine's name and its counts; it never materializes pairs of states.
`transitionsFrom` returns the identifiers declared from a state, `outgoingFrom` the `TransitionSpec`s
themselves, and `findTransition` the single spec keyed by `(from, id)`, as an `Optional` that is empty when
no edge is declared. `graph()` returns the `DirectedGraph<S, T>`, which keeps parallel edges and offers
`nodes()`, `edges()`, `outgoing`, `incoming` and a `topology()` projection. `initial()`, `terminals()`,
`policy(state)`, `watch(state)`, `submachine(state)` and `hasAsync()` are also available. None of these runs a
guard, an action, a hook or a sub-machine's lookup.

To export [Graphviz DOT](https://graphviz.org/doc/info/lang.html) from an existing ticket facade:

```java
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;

String diagram = GraphvizExporter.render(GraphView.of(tickets.definition()));
System.out.println(diagram);
```

This is a fragment: put the imports at file scope and the statements inside a method. Export does not run
transitions or evaluate guards, and there is no detail mode: the exporter draws the declared edges and no
other, because an edge nobody declared does not exist.

The export is deterministic DOT text with LF line endings. Each edge label begins with its identifier,
followed by the action name, `[async]`, `[if guard, …]`, one `[recover Kind -> ID]` per recovery rule in
declaration order, `[onFailure ID]` and any watch label. `Kind` is the failure kind's binary name without its
package, such as `PublicationFailures$Unavailable`. The throwable types of `failsOn` and of the rules are not
drawn, and a rule adds no arrow. Parallel edges appear as separate arrows with different labels, and a
self-loop is an arrow back to its own node. The initial state has an incoming arrow from a point, terminal
states have double outlines, and watches appear as notes connected by dashed lines. A node's rejection policy
is rendered as that node's `tooltip` — never as an arrow, because a refusal is an answer the node gives and
not a transition. State names and labels are escaped, and every declared state remains visible even when no
edge touches it.

A state that holds a sub-machine is drawn as a cluster labelled with the state and the child machine, such as
`DEPOSIT: Payment/PaymentStatus`, which holds the child's own diagram and has the state's policy as its tooltip.
An edge into the state points at the child's initial state, or, when that state holds a sub-machine too, at that
one's initial state, and so on down. An exit is drawn once from each terminal state of the child that selects it, and
any other edge leaving the state, like the state's watch note, starts at the cluster's border. The DOT then sets `compound=true` and clips those arrows with `lhead` and `ltail`. A graph that binds no
sub-machine has no cluster.

The shop's order machine exports as:

```text
digraph "Order/OrderStatus" {
    rankdir=LR;
    initial [shape=point, label=""];
    n0 [label="NEW", shape=ellipse, tooltip="otherwise: cannot ship without payment"];
    n1 [label="PAID", shape=ellipse, tooltip="otherwise: money was charged: requires a refund"];
    n2 [label="PAYMENT_DECLINED", shape=ellipse, tooltip="otherwise: go back to NEW to retry the charge"];
    n3 [label="SHIPPED", shape=doublecircle, tooltip="otherwise: a shipment cannot be undone"];
    n4 [label="CANCELLED", shape=doublecircle, tooltip="otherwise: cancelled is final"];
    initial -> n0;
    n0 -> n1 [label="PAY pay [async] [if retryBudgetLeft] [onFailure PAYMENT_DECLINED]"];
    n0 -> n4 [label="CANCEL cancel"];
    n0 -> n2 [label="PAYMENT_DECLINED"];
    n1 -> n3 [label="SHIP ship [if allItemsPacked] [watches Item: allIn PACKED]"];
    n2 -> n0 [label="RETRY [if retryBudgetLeft]"];
    n2 -> n4 [label="CANCEL cancel"];
    watch0 [shape=note, label="watches Item"];
    n1 -> watch0 [style=dashed, arrowhead=none];
}
```

Save the result as UTF-8 in `tickets.dot`, then use an installed [Graphviz](https://graphviz.org/download/)
to render an image:

```sh
dot -Tsvg tickets.dot -o tickets.svg
dot -Tpng tickets.dot -o tickets.png
```

The Java exporter needs no external library or native executable. Image rendering is a separate step.

You can also build a definition directly through `TicketsRuntime.graph(new TicketActions())` without constructing a
running set.

Use typed assertions on `GraphDefinition` and its `TransitionSpec`s, or `GraphSnapshots` with `.graph`
resources, for structural contracts. Validate behavior through real operations, outcomes, reactions and
observed entity states. A graphical export is never an oracle for machine logic. `GraphGoldens` with `.dot`
resources checks visual output only; keep those tests separate from contract and behavior tests. The
[testing guide](testing.md#goldens-and-structural-snapshots) describes both APIs and their Gradle and
Maven update configuration.

[The illustrated ticket workflow](images/ticket-workflow.svg) summarizes the quickstart's edges. The
exporter produces its own labels from the definition. The repository keeps exact expected exports in
[the example's golden diagrams](../graphmaquina-example/src/test/resources/golden).

The illustrations in this guide are maintained separately from the Graphviz exporter. Their accessible SVG
sources, with a table of what each one shows, are in [docs/images](images/README.md).

## Test your machines

Test through generated facades using real entities and deterministic action dependencies. The quickstart's
`App` is an executable smoke test that covers an applied edge, a guard rejection, a handled failure, a
request with no edge, a self-loop, a cycle and two parallel edges, and
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java)
shows JUnit tests for payment, retries, cascades, watches, journals, and the flow and the trail of a call.

The test below reads its outcome with `graphmaquina-test`, the module that holds the typed assertions. The
[install command](#configure-gradle) already wrote it to your local Maven repository alongside the runtime,
at the same version, and [settings.gradle.kts](#settingsgradlekts) already resolves it from there. For a
Gradle application using JUnit, add:

```kotlin
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.github.graphmaquina:graphmaquina-test:$graphmaquinaVersion")
}

tasks.test {
    useJUnitPlatform()
}
```

Create `src/test/java/com/example/tickets/TicketsTest.java`:

```java
package com.example.tickets;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;

import org.junit.jupiter.api.Test;

class TicketsTest {
    @Test
    void aDomainFailureCommitsItsDeclaredFailureState() {
        try (TicketMachines machines = TicketMachinesRuntime.builder(new TicketActions()).build()) {
            Ticket ticket = new Ticket("T-7", "Declined at intake", false);
            assertThat(machines.tickets().submit(ticket)).isFailed()
                    .hasTarget(TicketState.REVIEW)
                    .hasFailureTransition(TicketTransition.DECLINE)
                    .movedTo(TicketState.FAILED)
                    .hasEntityState(Ticket::getState, TicketState.FAILED);
        }
    }
}
```

`isFailed()` narrows the outcome and fails with the kind it actually got. `hasTarget` is where `SUBMIT` was heading,
`hasFailureTransition` is the identifier of the edge that recovered, `movedTo` is where that edge landed, and
`hasEntityState` reads the entity itself. Run `./gradlew test` in your application. The repository's
[ReadmeQuickstartTest](../graphmaquina-test/src/test/java/consumer/ReadmeQuickstartTest.java) extracts these literal
Gradle blocks and Ticket Java files, generates and compiles the main sources, runs `App`, and verifies this named JUnit
test and its result counts in an external Gradle consumer using locally staged artifacts. This verifies the local Gradle
workflow; it does not execute this JUnit sample through Maven or against released artifacts. This test consumes
generated main code; it does not declare test graphs and needs no `testAnnotationProcessor`. In Maven, add JUnit and a
compatible test runner through your application's usual test configuration, together with the test-scoped
`graphmaquina-test` dependency shown under
[typed assertions, probes and graph snapshots](#typed-assertions-probes-and-graph-snapshots); the
[install command](#configure-gradle) writes into the `~/.m2` repository Maven already reads, so it needs no repository
entry. Preserve the graph generation lifecycle for any test graphs.

### Typed assertions, probes and graph snapshots

`graphmaquina-test` is a test-scoped module that asserts over the runtime's own values — definitions, edges,
policies, outcomes, transition records, reactions, sub-machine starts, engine exceptions, and the flow of a call and
its trail — and reports every unmet
expectation as a `GraphAssertionError` carrying the context, the property, the expected value and the actual one. It
imposes no test framework: nothing in its API mentions JUnit or AssertJ, so it works under the runner your project
already uses.

The [install command](#configure-gradle) writes it with the other modules, and its POM pins
`graphmaquina-core` at the same version, which is why that command installs both in one invocation. The
coordinates take the same version variable as every other module:

```kotlin
dependencies {
    testImplementation("io.github.graphmaquina:graphmaquina-test:$graphmaquinaVersion")
}
```

```xml
<dependency>
  <groupId>io.github.graphmaquina</groupId>
  <artifactId>graphmaquina-test</artifactId>
  <version>${graphmaquina.version}</version>
  <scope>test</scope>
</dependency>
```

One static import of `io.github.graphmaquina.test.GraphAssertions.assertThat` reaches the view for every
subject a test holds, and the chain keeps the entity, state and transition types of that subject:

```java
assertThat(orders.pay(order)).isApplied().movedTo(OrderStatus.PAID)
        .hasEntityState(Order::status, OrderStatus.PAID);
```

```java
assertThat(orders.definition())
        .transition(OrderStatus.NEW, OrderTransition.PAY)
        .hasTarget(OrderStatus.PAID)
        .hasFailureTransition(OrderTransition.PAYMENT_DECLINED);
```

Beyond assertions it carries scripted probes for guards, actions, hooks and the state setter, fixtures that
let a test decide when an asynchronous callback answers, a recorder of completed calls, entity-state snapshots, and
golden comparison for both the rendered diagram and the complete structure of a graph. The
[testing guide](testing.md) covers installation, what each entry point checks, what each check does not
prove, and the golden update procedure for Gradle and Maven. Every sample in it runs in this repository: most
are copied from a test, and the samples of recovery rules, of sub-machines and of the call's flow are cut out of the
page and run by one.

Useful behavior to cover in your own domain:

- Every action's success and handled-failure result, including the actual entity state.
- Each guard rejection, and each identifier a state refuses, with the reason its policy gives.
- Two operations that reach the same state, so neither can quietly stand in for the other.
- Every self-loop you declare: it applies, and its hook runs.
- Every recovery rule, through a returned failure of its kind and through each throwable type it declares, and the
  most specific type winning where declared types overlap.
- Throwables that a recovery rule or `failsOn` maps, and those that must abort.
- Cascade rollback behavior and collected error details.
- First-match watch order, empty peer collections and aborted reactions.
- Async timeout behavior and late completions.
- Hooks that fail after state has committed.
- Declared graph contracts through typed assertions or structural snapshots.

Visual export tests may separately pin `.dot` goldens when changes to diagram output require review.

A passing runtime test does not replace compile-time graph validation. Conversely, a valid graph does not prove
that your action performs the correct domain operation.

## Generation lifecycle and IDE setup

GraphMaquina uses pre-compilation generation, because the annotations and symbols used by your source must
exist before javac can type-check that source:

```text
generate -> prepare-compile -> javac (Lombok if used, then GraphMaquina) -> complete-compile
```

The source-reading step uses `JavacTask.parse()` with annotation processing disabled; it does not type-check
the original application or require its generated annotation classes. It parses the original sources at the
compilation's release and in its source encoding, which the build adapters pass in the request, and it writes
the generated sources in that encoding too. It resolves the headers and exported members needed for
generation, then emits vocabulary plus a manifest. The processor verifies that the manifest matches the
declarations javac accepted, resolves bindings, checks the graph and emits facades, in ASCII only: every other
character of a name or a text is written as a `\uXXXX` escape, so javac writes them alike in any encoding.
Completion publishes the validated export index and records ownership of generated outputs.

### Generated files

| Content | Gradle | Maven |
| --- | --- | --- |
| Main vocabulary / symbols | `build/generated/sources/graphmaquina/main` | `target/generated-sources/graphmaquina` |
| Test vocabulary / symbols | `build/generated/sources/graphmaquina/test` | `target/generated-test-sources/graphmaquina` |
| Main processor-generated facades, default Java compiler location | `build/generated/sources/annotationProcessor/java/main` | `target/generated-sources/annotations` |
| Main work directory | `build/graphmaquina/main` | `target/graphmaquina/main` |
| Test work directory | `build/graphmaquina/test` | `target/graphmaquina/test` |
| Published main export index | `build/classes/java/main/META-INF/graphmaquina/exports.json` | `target/classes/META-INF/graphmaquina/exports.json` |

Work directories contain `request.json`, `annotation-manifest.json`, ownership inventories and processor
inventory. A failed generation can leave `annotation-manifest.pending.json` for ownership recovery; this is
not a manifest javac should consume.

Do not hand-edit, commit or copy these generated outputs into `src/main/java`. Preserve the export index when
packaging a producer JAR. Normal rebuilds retire owned outputs for renamed or deleted declarations without
requiring `clean`; handwritten files in output locations are not silently adopted as generated files.

Gradle adds generated vocabulary to the source set's java sources as a source directory set of its own, so
source archives and IDE imports can see it; the source set's include and exclude patterns do not filter it, and
the sources jar and javadoc of the source set leave out the compilation carrier. The plugin disables
incremental compilation on the compile task of every source set so partial javac output does not conflict with
complete ownership inventories. Gradle task up-to-date checks and configuration-cache support are separate;
unchanged builds can still skip work.

### IDE workflow

1. Import the project through its Gradle or Maven model, using the intended Java 21 JDK.
2. For a minimal machine still being written, run `./gradlew generateStateGraphApi` or `mvn generate-sources`
   to create vocabulary first. Once the flow is complete, run `./gradlew compileJava` or `mvn compile` to
   validate it and create runtime implementations.
3. Refresh the build model so generated source roots are visible.
4. After renaming graph declarations or bindings, run the normal build to regenerate references and retire old
   outputs. Fix handwritten references to the renamed symbols as required by javac.

An IDE's plain javac invocation is insufficient if it bypasses the pre-generation adapter. Delegate builds to
the configured build tool or run that lifecycle explicitly. Do not fix missing generated symbols by creating
empty source files with those names.

The automated build checks cover generated roots. Manual IntelliJ editor/navigation/rename acceptance remains
pending; see [known limitations](#known-limitations).

## Multiple modules and published JARs

A producer built through the complete lifecycle packages its accepted graph exports in
`META-INF/graphmaquina/exports.json`. A consumer can use exported symbols and include published machines in its
set without needing the producer's sources. Generated dependencies remain typed and are validated against the
producer index.

For a multi-module build:

1. Configure generation and the processor in each module that declares or consumes generated graph APIs.
2. Build the producer before the consumer and declare it as a normal dependency.
3. Package the generated classes and export index together. Avoid assembly steps that silently drop or
   overwrite `META-INF/graphmaquina` metadata.
4. Regenerate and recompile consumers after changing a producer's exported API. A stale or missing index is a
   build error: one that is missing, or that does not list a machine or a symbol the classes beside it carry, is
   reported as `SG602` wherever the consumer uses that machine or symbol, naming the jar or directory of classes
   that carries it.

Support for published symbols and sets does not imply support for a watch whose pair spans separate
compilations. Keep watched owner and peer declarations in the same compilation until
[issue #1](https://github.com/graphmaquina/graphmaquina/issues/1) is implemented.

## Programmatic DSL

The typed annotation API is the main application workflow. The core artifact also includes a Java DSL under
`io.github.graphmaquina.dsl`, which constructs a `GraphDefinition` at runtime. Its graph validation happens
when `build()` runs, rather than during javac. The DSL entry point is `StateGraph.define(key)`.

The following complete utility can be added to the ticket quickstart package. It creates an alternative
programmatic definition using the same entity and enums, without using its generated facade:

```java
package com.example.tickets;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;

public final class ProgrammaticTickets {
    private ProgrammaticTickets() { }

    public static Outcome<Ticket, TicketState, TicketTransition> run(Ticket ticket) {
        MachineKey<Ticket, TicketState, TicketTransition> key =
                MachineKey.of(Ticket.class, TicketState.class, TicketTransition.class);
        GraphDefinition<Ticket, TicketState, TicketTransition> graph = StateGraph.define(key)
                .initial(TicketState.OPEN)
                .terminal(TicketState.DONE)
                .terminal(TicketState.FAILED)
                .allowsCycle(TicketState.OPEN)
                .statePolicy(TicketState.OPEN, "submit the ticket first")
                .statePolicy(TicketState.REVIEW, "approve, reject or decline the ticket")
                .reject(TicketState.OPEN, TicketTransition.APPROVE, "approval requires review")
                .transition(TicketTransition.SUBMIT, TicketState.OPEN, TicketState.REVIEW)
                    .guard("hasTitle", ctx -> ctx.entity().getTitle().isBlank()
                            ? GuardResult.reject("a ticket needs a title") : GuardResult.pass())
                    .endTransition()
                .transition(TicketTransition.APPROVE, TicketState.REVIEW, TicketState.DONE)
                    .action("approve", ctx -> ctx.entity().isAccepted()
                            ? ActionOutcome.ok() : ActionOutcome.failed("approval declined"))
                    .onFailure(TicketTransition.DECLINE)
                    .endTransition()
                .transition(TicketTransition.DECLINE, TicketState.REVIEW, TicketState.FAILED)
                    .endTransition()
                .transition(TicketTransition.REJECT, TicketState.REVIEW, TicketState.OPEN)
                    .endTransition()
                .build();

        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph)
                        .state(Ticket::getState, Ticket::setState)
                        .identity(Ticket::getId))
                .build()) {
            registry.of(key).execute(ticket, TicketTransition.SUBMIT);
            return registry.of(key).execute(ticket, TicketTransition.APPROVE);
        }
    }
}
```

Add `import io.github.graphmaquina.api.GuardResult;` for the guard above. Every stage names an identifier:
`transition(id, from, to)` opens one edge and `endTransition()` closes it. `recover(kind, id)` names the recovery for a
returned failure of that kind or a throwable whose most specific declared type is one of the rule's; `onFailure(id)`
names the fallback for every other returned failure and for a throwable whose most specific declared type is one of the
edge's `failsOn`. Recovery identifiers resolve from the failing edge's origin. `statePolicy` / `reject` word the answers
a node gives. A state has exactly the edges you declare. An edge left open, or one that declares more than one action,
`onFailure` or `timeout`, fails `build()` with `SG507`. An edge's stage refuses every further call with
`IllegalStateException` once `endTransition()` closed it, and once `build()` has run, even a `build()` that failed,
every stage refuses further declarations, and a second `build()`, the same way.

The DSL exposes runtime functions directly, so this alternative does not need generated action symbols. It
deliberately omits the quickstart's self-loop, auto-approval and commit hook; add them through the DSL when
that behavior is required.

`submachine(state, child, childOf, parentOf)` binds another machine to a state: `child` is that machine's own
`GraphDefinition`, `childOf` returns the child entity of a parent and `parentOf` the parent of a child, both as an
`Optional`. An edge that enters `state` while `childOf` answers empty aborts its step before the action runs, as
[compose machines](#compose-machines) says. `submachine(state, child)` binds a machine over the same entity, whose
lookups return the entity itself.
`exit(terminal, id)` maps a terminal state of the child to an identifier of the parent, its exit, and
`endSubmachine()` returns to the graph. `build()` checks the bindings (`SG801` to `SG805`), and the definition's
`submachine(state)` returns the binding of a state. A registry that holds the parent must hold the child's machine
too, built from that very definition: otherwise `MachineRegistry.Builder.build()` throws `IllegalStateException`, and
so does a standalone `build()`, whose registry holds no other machine. Every machine whose calls can run an edge
implicitly must also declare `asyncTimeout(...)` when that edge is asynchronous, including a child's binding exit
and a terminal initial descendant's exit started by the caller's own entry, as
[Deadline rules](#deadline-rules) explains. [Compose machines](#compose-machines) describes the whole contract with an
annotated example.

Other stages support named guards, async guards, async actions, `failsOn`, `recover`, `timeout`, `afterCommit`,
`afterCommitWith`, watches, sub-machines and cycle allowances. A guard is named by the name given or, without one, by
the simple name of its class, and `build()` refuses a second guard of one name on an edge with `SG105`, as it refuses
the same guard declared twice. A low-level `StateMachine` exposes
`execute(entity, id)`, `execute(entity, id, input)` and `async().execute(...)`; a generated application
facade intentionally presents the domain operations instead. Keep registry lifetime explicit to close owned
executors.

## Diagnostics and troubleshooting

Read the full compiler message, including the source location and member it identifies. A wrong enum type or
symbol category may be an ordinary javac error rather than an `SG` diagnostic: writing a state where an
identifier belongs, or a constant of another machine's catalogue, is refused by javac before any GraphMaquina
code runs. A mistyped generated symbol, such as `DocActionsRefs.Publsh.class`, is javac's alone to refuse too, as
`cannot find symbol` once annotation processing is over: the processor still reports every other problem of that
machine's declarations, but none about its graph as a whole, and generates no companion for it. A class another
annotation processor generates can still be named, such as an exception in `failsOn`, and so can a constant it
declares, such as the text of a policy, the reason of a rejection or the seconds of a timeout: the processor builds
that machine, and a set that holds it, in the round that brings the class, and a constant no round resolves is
javac's alone to refuse, like a mistyped symbol. javac refuses such a constant before any processor runs, as
`expression not allowed as annotation value`, in an annotation repeated without its container: list those
annotations in their container instead, such as `@DocsGraph.StatePolicies({...})`. The values of annotations
GraphMaquina does not own are left to javac, so an annotation of your own on a facade may even name the machine's
companion.

### Graph and declaration diagnostics

| Code | Meaning / corrective action |
| --- | --- |
| `SG102` | Duplicate `(from, id)` key, or a state that declares two policies or rejects one identifier twice. Two edges that merely share endpoints are not a duplicate. |
| `SG103` | Missing or blank reason on a policy or a rejection. |
| `SG105` | Duplicate guard: list each guard once per transition, and give the guards of one transition distinct names, which a rejection reports. A generated guard takes its member's simple name, so one transition cannot ask for two hosts' guards whose members share a name; a DSL guard takes the name given or, without one, its class's simple name. |
| `SG106` | DSL callback has no inferable name: provide the explicit name overload for a lambda. |
| `SG107` | Blank DSL callback or condition name: supply a nonblank label. |
| `SG201` | Missing or repeated DSL initial declaration. |
| `SG202` | A terminal declares an outgoing edge, including a self-loop, or a non-terminal declares none. |
| `SG203` | Unreachable state: add a real path from initial or remove the state. |
| `SG204` | Unauthorised cycle: list an intentional cycle's state in `allowsCycle`. One witness is reported per cyclic strongly connected component, deterministically, rather than one message per elementary cycle. |
| `SG205` | Unused cycle allowance: the named state belongs to no cycle. |
| `SG301` | Invalid failure route: `onFailure` or a recovery rule names this edge's own key, or no edge with that identifier leaves the same origin. Each origin of an identifier is checked on its own. |
| `SG302` | Forbidden throwable type in `failsOn` or in a recovery rule: `Throwable`, `Exception`, `RuntimeException`, an `Error`, a library exception, a `CancellationException` or one of its subtypes, or exactly `CompletionException` or `ExecutionException`, which the runtime unwraps before matching whenever they carry a cause. In the DSL, also a class that is not a `Throwable`, which only a raw type lets through. Use a specific foreign throwable type, usually an exception, or the type of the wrapped cause. |
| `SG303` | A recovery rule names a class that is not a failure kind. A failure kind is a named final class or record that implements `FailureKind` and is not an interface, an enum, a local or anonymous class, or a `Throwable`: declare one as `public record Unavailable() implements FailureKind { }`. |
| `SG304` | Ambiguous recovery rules: two rules of one edge name the same failure kind, or one throwable type is declared twice in a rule, in two rules, or in a rule and in the edge's `failsOn`. A type that only `failsOn` repeats is accepted. |
| `SG401` | A watch rule names an identifier with no edge from the watched state. |
| `SG402` | A terminal state declares a watch. |
| `SG403` | More than one watch on the same state. |
| `SG404` | A watch has no rules. In the DSL, also a `when(...)` that no `execute(...)` follows, and an `execute(...)` with no pending `when(...)`. |
| `SG502` | Invalid machine or set declaration, such as an unsupported nested or local interface, a machine declared as a class instead of an interface, or a set that is not an interface extending `Machines`. A machine interface that does not extend `Machine` is `SG601`. |
| `SG503` | Missing or incompatible public property accessor. |
| `SG504` | Invalid operation annotation or signature. An operation is `Outcome<E, S, T> operation(E)` or `operation(E, I)` and carries at least one `Transition` of its own vocabulary. Zero, three or more parameters and a wrong entity or return type are rejected; operations with input also reject varargs and method type parameters. Also refused: a `Transition` on a default, static or private method of the facade; an abstract method the facade inherits from another interface, which its companion would not implement; a method that redeclares a `Machine` method with vocabulary of its own, or with a result the companion cannot return; and an instance method of the facade, declared or inherited, abstract or default, with the signature of the companion's static `graph(...)`, which takes the definition of each machine it binds as a sub-machine, then one parameter per class with instance members the machine binds, or none. That last refusal is reported once the machine's declarations have no other error and none of its vocabulary is misplaced, since fixing one can change what `graph(...)` takes; an error of its graph as a whole, such as `SG202`, `SG203` or `SG701`, and a refused companion name (`SG603`, or `SG602` when a previous compilation left its companion) are reported beside it. |
| `SG505` | Exported method does not uniquely fit the action, guard, hook, condition or lookup role, including the default `afterCommit` binding on `Definition`. Also an instance member of a generic host, or of an inner class of a generic class, which generated code could only call raw (export it from a non-generic subclass marked `@GraphBindings`, or make it static), and a binding whose host or parameter types the companion's package cannot access. |
| `SG507` | Invalid transition elements: empty `from`, `onFailure`, `failsOn` or recovery rules without an action, or a timeout without an asynchronous action. In the DSL, also an edge never closed with `endTransition()`, and an edge that declares more than one action, `onFailure` or `timeout`. |
| `SG508` | Watch rule must choose exactly one supported predicate alternative. |
| `SG509` | Invalid peer-machine type, a peer whose entity is a parameterized type, a watched peer whose types the watcher's package cannot access, or a sub-machine whose entity, states or transitions its parent's package cannot access. |
| `SG510` | Invalid set accessor, duplicate/missing machine or missing watched peer in the set. Also an accessor the set inherits from another interface, two machines that write the same entity and state enum, a machine whose types the set's package cannot access, and an instance method of the set, declared or inherited, abstract or default, with the signature of the companion's static `builder(...)`, which takes the timeout shared by the set's asynchronous machines, when it has any, then every class with instance members its machines use. That last refusal is reported only once the set's accessors and machines have no other error, since fixing one can change what `builder(...)` takes. |
| `SG511` | Invalid timeout duration: it must be positive. |
| `SG701` | A state declares no rejection policy. |
| `SG702` | One `(from, transition)` key both declares an edge and rejects the identifier. |
| `SG703` | Two annotations of one method name different identifiers, or two methods claim the same identifier. A type-level `Transition` may add branches of an identifier a method owns while their keys stay disjoint. |
| `SG801` | A sub-machine's exits do not match the terminal states of its child: a terminal state of the child has no exit or more than one, or an exit is declared on a state that is not terminal in the child. Two terminal states may map to the same identifier. |
| `SG802` | A sub-machine's exit names an identifier with no edge from the state the sub-machine is bound to. |
| `SG803` | A terminal state holds a sub-machine, or one state holds more than one. |
| `SG804` | An identifier that a sub-machine's exit names is also named, from the state the sub-machine is bound to, by an edge's `onFailure` or recovery rule, or by a rule of that state's watch. The same identifier from another state is not an exit. With annotations, an operation of the facade may not execute an exit either: it requests its identifier from whichever state the machine is in, the bound state included. |
| `SG805` | A machine's sub-machines contain the machine again, directly or through their own sub-machines. The DSL compares machine keys along the chain of child definitions, so it takes a second definition of the machine's own key, and prints one shortest path. The processor follows the sub-machines each child declares in turn, and reports one shortest path at the binding it starts with, on every machine of the cycle. |
| `SG806` | A sub-machine declared with annotations does not declare the ways between its entities that it needs: a child over another entity than the parent's needs `child` and exactly one of `parent` or `parentLookup`, and a child over the parent's own entity takes none of the three. Also a set that holds a machine but not the machine bound to one of its states, which the set could not build, whether the parent was generated by this compilation or comes from a dependency jar. |

### Generation and typed binding diagnostics

| Code | Meaning / corrective action |
| --- | --- |
| `SG599` | The processor could not write a generated source, provisional export index or processor inventory. Inspect the named type/path and underlying error; correct the output path, permissions, available space or reported file collision, then rerun the full generation/compilation lifecycle. See [processor output failures](typed-annotations-build.md#processor-output-failures-sg599). |
| `SG601` | Bootstrap declaration cannot be resolved, is ambiguous or contradicts another declaration. `Machine<E, S, T>` must resolve to a concrete entity type and **two distinct enums**, one for states and one for transition identifiers. Also a facade that does not extend `Machine`, such as `interface Tickets extends Runnable`, a facade that declares type parameters, a parameterized entity type, such as `Ticket<String>` or an inner class of a parameterized class, such as `Store<String>.Doc`, a facade or enum the vocabulary's package cannot access, a marker whose annotation type is not on the generator's classpath, and an original source javac cannot parse at the compilation's release. |
| `SG602` | A manifest, an inventory or an export index is missing, incompatible, stale, malformed, or written in the retired schema 1. Any of these files, or the request, is malformed when it holds a number whose exponent is out of range, such as `1e99999999999`, or escapes in a string or a name a surrogate that is not half of a pair, such as `\ud800`, which no UTF-8 file can hold, and a manifest is also malformed when its processor inventory is no path, such as one holding a NUL character, or when an owned source it lists is not a relative path to a Java source: one that no path can spell, such as one holding a NUL character, one that is absolute or has a root, one with a `..` segment, one that names the directory itself, such as `.`, or one that is no `.java` file, such as `x`. A dependency, a jar or a directory of classes, that carries no export index, as an assembly step that drops `META-INF/graphmaquina` leaves it, or whose index does not list a machine or a generated symbol its classes carry, because it is stale or another build's, as an assembly step that overwrites `META-INF/graphmaquina` leaves it, is reported where a set holds such a machine or a declaration binds such a symbol, naming that jar or directory: package the index its StateGraph build publishes with its classes, or rebuild the dependency through the complete lifecycle. Run the full generation/compilation lifecycle with aligned versions; a build upgraded in place, or one whose manifest is malformed, needs one `clean`. Also a machine javac compiled implicitly, from the source path, which the processor never saw, reported where the index or a set names it: pass every source of the compilation to javac. A companion of a previous compilation that nothing retired is reported too: run the lifecycle, or clean. Completion reports a class output without the `SourceFile` attribute: compile with javac's default debug setting, with `-g`, or with a `-g:` list that includes `source`. The generator also refuses a source encoding that names no charset of the JVM it runs under: name one that JVM supports, such as `UTF-8`. |
| `SG603` | Generated output or export collision, a name generated code cannot write, or an output path the generator may not use. Two names normalize to the same PascalCase spelling; a generated type already exists as an original source, in a file at the same path below its source root as the generated file below the generated one, or in another file, which javac would report as a duplicate class, as a class of the classpath, or as a file of the generated directory the previous generation did not own; an original file or type differs from a generated one, or two generated types or two symbol types of one container differ from one another, only in case where the volume does not tell names apart; an existing type takes a companion's name; a type, member type or constant in the scope of a generated file takes over a name that file writes, such as a type of its package named `io`, `java` or like a `java.lang` type; or one canonical name names two types, because a class of the unnamed package is named like a package: two machines, one of the package and one nested in the class, which a manifest names by that name alone, or two types in a class file of the unnamed package that the generator has to tell apart, such as two of its supertypes, which it cannot do by their canonical name. The diagnostic names both sides; rename one of them and its references. The generator's steps also refuse an inventory entry that is not a file under its output root, such as a blank one, one that no path can spell, as with a NUL character, or one that is absolute, has a root, names the root itself or leads out of it, and a binary that is no `.class` file or a source that is no `.java` file; an inventory entry whose file they would remove through a symbolic link inside its output root that leads out of it; and a generated source that would be written through such a link. Remove the link; clean a build whose inventory holds such an entry. |
| `SG604` | Invalid or contradictory export/role, or a host its generated references container cannot access, such as a private nested class, or cannot name at all: a marker written in a local or anonymous class, on the class or on one of its members. There, as javac reads it, an annotation named like a marker means a member type of a class around it first, such as one inherited from a supertype, and is then no marker; one inside a class whose supertype the generator cannot resolve is let through. Also a static method exported through a `@GraphBindings` host that hides it, which the generator could not tell because a parameter is a type variable. Check markers and exported signatures. |
| `SG605` | Symbol has no valid original member, or belongs to the wrong host/role. Use the correct generated reference. |
| `SG606` | Graph vocabulary or the vocabulary of a pair belongs to another machine or pair, or sits where no machine reads it: on anything but a `@GenerateGraphApi` interface and its abstract operations. On a default, static or private method of its own facade it is `SG504`. |
| `SG607` | Too many values in an optional singleton element such as `action`, `onFailure`, `timeout`, `afterCommit`, `When.condition`, or the `child`, `parent` or `parentLookup` of a `Submachine`. `recoveries` is no such element: it holds any number of rules, and several rules are never several fallbacks. |
| `SG608` | Invalid or duplicated peer, a peer that no watch or sub-machine of the machine uses, a watch or a sub-machine of a peer the machine does not declare, a peer outside this compilation, or a peer whose types the owner's package cannot name. |
| `SG609` | A retired 1.x graph annotation is still on the class path and still used. Replace it as [migrating to 2.0](migration-2.0.md) describes. |

### Runtime exceptions

| Exception | What to investigate |
| --- | --- |
| `UninitializedStateException` | Entity state was null. Initialize or correctly hydrate it before calling the machine. A null root state arrives as the cause of an abort in `RESOLVE`; a peer's, reached by a cascade, as the cause of an abort in `ACTION`, and so does a sub-machine's, read when its parent enters the bound state. A parent's, read to check the binding of a sub-machine, arrives as the cause of an abort in `RESOLVE` when the sub-machine is the root and in `ACTION` inside a cascade or an exit. |
| `TransitionAbortedException` | A technical failure aborted execution; inspect cause, path and action journal. Its `from()`, `transition()` and `to()` are `Optional`. An abort raised before the entity's state was read, because its identity or its state could not be read, reports no state and no destination. One raised before any edge was resolved reports no destination, and one raised before any identifier was read, such as a watch chain over its limit, reports neither identifier nor destination. Its `phase()` is `RESOLVE`, `GUARD` or `ACTION`, and its `statesWritten()` is `NONE`. |
| `UnhandledFailureException` | An action failed, no recovery rule took the failure, and its edge has no `onFailure` route. The step was rolled back first, so its `flow()` lacks what that step and the steps it cascaded to wrote. |
| `CascadeException` | Collected nested transition errors; inspect `errors()` and the journal. |
| `CascadeCycleException` | A nested request reached an entity that is already in flight. It reports the in-flight and requested identifiers. |
| `CascadeDepthExceededException` | Nested work exceeded the configured cascade depth. |
| `WatchChainExceededException` | A watch chain exceeded its limit; inspect reaction cycles. |
| `AmbiguousIdentityException` | Different instances represented one machine identity in the same unit of work. Raised by an action's cascade, or by the start of a sub-machine whose `childOf` answers an instance with the identity of another one already in the call, it arrives as the cause of that call's abort. A sub-machine whose `childOf` answers another instance with the identity of the child being called raises it too, and so does one whose `parentOf` answers another instance with the identity of a parent already in the call. |
| `AmbiguousParentException` | A sub-machine's entity was held by two bindings at once: two parents, each in its bound node or leaving it. A parent that leaves one bound node for another that binds the same child is not two: it holds the child only in the node it enters. It names the child machine and both parent machines and nodes. It arrives as the cause of the call's abort, of phase `RESOLVE` for the root's own step and `ACTION` inside a cascade or an exit. |
| `ReentrantTransitionException` | A guard, an action, a state accessor, an identity function, a sub-machine's `childOf` or `parentOf`, or a watch's owner lookup, peer lookup or condition, or an observer, called `execute` or `reevaluate`. An action moves peers through `ctx.cascade(...)`; hooks may start transitions; observers and the other callbacks may not on their own thread. Inside a call it arrives as the cause of the call's failure: an abort when a guard, an action, a state getter, an identity function, a sub-machine's `childOf` or `parentOf`, or a reevaluation's peers reader or condition throws it, and a `CommitFailedException` when a setter, or the getter during the commit's check, does. An owner lookup's is what its `Reaction.Aborted` carries, and an observer's is contained. |
| `PoisonedUnitOfWorkException` | A context's `peer`, `cascade` or `cascadeAll` was called after a failed cascade poisoned the transaction; `original()` is that failure. When the action lets this exception through, or the engine has the action's outcome, while the transaction is poisoned, the engine throws the failure that poisoned the transaction; the `PoisonedUnitOfWorkException` Javadoc says what else can end the call. |
| `StaleContextException` | A context or context-derived view was used after invalidation, or the flow of a hook after that hook returned or threw. Used by a guard or an action of another call, it arrives as the cause of that call's abort. |
| `java.lang.WrongThreadException` | Context peer/cascade/flow access, a derived peer view, or an operation of a context's flow or of a hook's flow was used from another thread; it can appear as the cause of an abort or of an `AfterCommitException`. |
| `UnknownFacadeException` / `UnknownPeerMachineException` | The requested peer was not registered in the set/registry. Thrown by a guard or an action, it arrives as the cause of that call's abort. |
| `CommitFailedException` | Phase `COMMIT`: the commit stopped before its first setter, because the pre-commit check failed or reading the states or grouping the log by entity threw (`NONE` written), or a setter threw (`PARTIAL`); inspect `applied()`, `failedEntity()` and `pending()` before retrying. |
| `AfterCommitException` | A hook threw: phase `HOOK`, every state written; `hop()`, `records()` and `starts()` say what. |
| `InvalidGraphException` | Programmatic DSL definition failed validation; inspect its problem list. |

A refused request is not an exception. `Rejected` with `RejectionKind.NO_TRANSITION` is the ordinary answer
when no edge carries the requested identifier from the entity's current state. These are not HTTP status
codes either: map rejected transitions, business failures and technical failures to your application's own
API contract.

### Common build problems

| Symptom | Remedy |
| --- | --- |
| `TicketsGraph` / `TicketRefs` cannot be found | Run generation before javac; check processor participation and original source roots. |
| A graph annotation from a 1.x declaration cannot be resolved | The retired vocabulary was removed in 2.0, not deprecated. [Migrating to 2.0](migration-2.0.md) names the replacement for each one. |
| `SG602` on the first build after upgrading | The work directory still holds schema 1 artefacts. Run `clean` once, or delete the build directory. |
| Gradle plugin cannot resolve | Declare the repository that holds it in `pluginManagement.repositories`: `mavenLocal()` for a version you installed from a checkout, `mavenCentral()` for a published one. |
| Core resolves but Maven plugin does not | Configure `<pluginRepositories>` as well as `<repositories>` for an internal repository. |
| Processor reports no manifest | Use the Gradle plugin or add Maven's per-execution manifest arguments and six goals. |
| A Lombok accessor is missing | Put Lombok first on the processor path, or provide a public handwritten accessor. |
| A newly renamed symbol still appears | Run the normal lifecycle, check whether generation succeeded, and inspect the original declaration and ownership diagnostics. |
| Generation task skips | That source set does not declare `graphmaquina-processor` on its annotation-processor configuration. |
| Async graph cannot be built | Supply a positive default timeout through the generated set builder. |
| Watch never triggers | Check relationship accessors, owner state, first-match rules, the named edge's guards and whether a peer transition actually committed. |
| Strict lint reports unclaimed third-party annotations | javac's `processing` lint, which `-Xlint:all` turns on, warns, in a compilation that runs annotation processors, about the annotations of its sources that no processor claims, the platform's own such as `@Override` aside, and `-Werror` turns the warning into an error. GraphMaquina's processor claims only GraphMaquina's own annotations; it is not a wildcard processor for Spring, Jakarta or test annotations, such as JUnit's `@Test` in a test compilation it runs in. It does not claim `javax.annotation.processing.Generated` either, so another tool's `@Generated` that no processor claims gets that warning too; GraphMaquina's companions carry its own `@GeneratedCompanion` instead. Turn the lint off for that compilation with `-Xlint:-processing`, or `-processing` in its `-Xlint:` list, as the [Maven POM](#pomxml) does for its test compilation. |
| Strict lint reports, in generated code, an auxiliary class accessed from outside its own source file | A host, or the class it is nested in, is declared in a source file named after another type, and generated code, such as the host's references container, names it from another file. javac's `auxiliaryclass` lint, which `-Xlint:all` turns on, warns about that, and `-Werror` turns the warning into an error. Give that class a file of its own, or turn the lint off with `-Xlint:-auxiliaryclass`. |

For a reproducible issue, include JDK/build-tool versions, relevant build configuration, the minimal graph and
binding declarations, the full diagnostic and whether the problem occurs on a clean build. Report issues at
[GitHub Issues](https://github.com/graphmaquina/graphmaquina/issues).

## Known limitations

| Area | Current status |
| --- | --- |
| Upgrade from 1.x | Deliberately incompatible. There is no alias of the retired API, no compatibility mode and no conversion of stored snapshots; [migrating to 2.0](migration-2.0.md) maps every contract. |
| Watches and sub-machines between JARs/modules | Unsupported with annotations: both machines must be generated in one compilation. External peers fail with `SG608`; [issue #1](https://github.com/graphmaquina/graphmaquina/issues/1). |
| State hierarchy and parallel regions | A state can hold a [sub-machine](#compose-machines): another machine bound to it, which entering the state starts and whose terminal state selects the parent's exit. Parallel or orthogonal regions, a state that holds two machines at once, are not modelled. |
| Event scheduling and automatic path execution | Absent. Each operation resolves and executes one edge, plus the cascades its actions request and the exits of the sub-machines it ends; nothing walks a path or fires on a timer. |
| Unauthorised-cycle reporting | One witness per cyclic strongly connected component, not one message per elementary cycle. `Graph.cycles()` itself is unchanged. |
| IDE acceptance | Generated-root build integration is automated; manual IntelliJ navigation, editor completion and rename/regeneration checks remain pending. |
| External payments demo | Its strict-lint acceptance is still partial; it is outside this repository's library test totals. |
| Durability and concurrent access | Application responsibilities; there is no automatic DB transaction, durable watch queue or entity lock. |
| Async timeout | Stops waiting; does not terminate synchronous code or cancel a user's running stage. The wait's callback stays registered on that stage until it completes; later waits for the same stage object share it, so the stage carries one however many waits give up on it, plus one per wait already in progress when the first gave up. A new wrapper per call is a new stage. |
| Preview language features | The generator parses the original sources without `--enable-preview`, whatever javac is given: a source using a preview feature that javac's parser checks is refused with `SG601`. See the [build guide](typed-annotations-build.md#limits). |
| Kotlin sources | The generator parses Java sources only: a marker on a Kotlin declaration is never read, and in a Gradle source set that mixes Kotlin and Java, a Java machine over an entity or a state enum a Kotlin source declares is refused with `SG601`. See the [build guide](typed-annotations-build.md#limits). |
| Java modules | `graphmaquina-core` is an automatic module, `io.github.graphmaquina.core`: javac warns about a `requires transitive` of it by default, and about a plain `requires` under `-Xlint:all`. See the [build guide](typed-annotations-build.md#limits) for the lint keys to turn off. |
| Public compatibility matrix | Java 21 is the baseline; no claim of verification across all newer JDKs or Gradle versions. |

## Build, verify and release this repository

These commands run from the GraphMaquina checkout, not the ticket application's directory:

```bash
./gradlew --no-daemon build
./gradlew :graphmaquina-core:publishMavenPublicationToAdapterTestsRepository \
          :graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository \
          :graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository
./gradlew --no-daemon test :graphmaquina-gradle-plugin:test
./gradlew --no-daemon :graphmaquina-test:publishedArtifactTest --offline
./gradlew :graphmaquina-example:test
./gradlew :graphmaquina-codegen:test :graphmaquina-processor:test
./gradlew :graphmaquina-maven-plugin:test
```

Stage the sibling artifacts before running the Gradle adapter's TestKit suite, especially in a fresh checkout
or after changing versions. This uses a file repository under `build/adapter-test-repository`.
The combined `test` command explicitly includes that suite. Its plugin is a separate included
build, while the Maven adapter's integration suite drives actual Maven builds. Have `mvn` available to run it.
The example applies the included plugin and uses the sibling generator project.

`./gradlew test` runs the ordinary suites for core, processor, codegen, example, Maven adapter and testing
support. In `graphmaquina-test`, it excludes `PublishedArtifactSmokeTest`, `ReadmeQuickstartTest`,
`GraphOnlySurfaceTest`, `GuideOutcomeFragmentsTest` and `TestingGuideFragmentsTest`. `./gradlew build` reaches all
five through `check` →
`:graphmaquina-test:publishedArtifactTest`, which stages core, testing support, generator, processor, Maven
plugin, Gradle plugin and its marker into the local file repository.
The published-artifact task also runs independently with the command above. Its external README consumer
resolves dependencies offline, so third-party dependencies must already be cached.

The included Gradle adapter's suite requires the explicit `:graphmaquina-gradle-plugin:test` command;
`build` does not run it. Ordinary test reports are under each module's `build/reports/tests/test`, and the
artifact smoke reports are under `graphmaquina-test/build/reports/tests/publishedArtifactTest`.

### Release to Maven Central

Releasing is a maintainer operation. The version comes from git tags, so a release creates a tag and edits no
file. The next release is `2.0.0`, not another `1.0.x` patch: the graph-only contract is incompatible with
1.x, so every module moves to that version together. A published version can never be replaced or deleted.

To build and check a bundle without publishing it, configure `SIGNING_KEY` with the project's ASCII-armored
private release key and `SIGNING_PASSWORD` with its passphrase, then run:

```bash
./gradlew --no-daemon centralBundle
```

Without `-PgraphmaquinaVersion`, that stages the most recent tag's version, which is already published; pass
the flag to stage a different one. Maintainers using 1Password CLI can inject both secrets for this command
without copying them into the checkout or shell history. Replace `<vault>` and `<item>` with the vault and
signing item's names or IDs; the item must have a `Private PGP key` field containing the armored secret key
and a `password` field. 1Password shows that field under two names, the id `private_key` and the label
`Private PGP key`; they are one field, and these examples address it by its label:

```bash
SIGNING_KEY='op://<vault>/<item>/Private PGP key' \
SIGNING_PASSWORD='op://<vault>/<item>/password' \
op run -- ./gradlew --no-daemon --no-configuration-cache centralBundle
```

The `op://` values are references, not credentials. `op run` resolves them into the child process's
environment and masks secrets in its output. Keep the private key, passphrase and revocation certificate
in the vault. Only distribute the public key. See [release signing](release-signing.md) for the
public fingerprint, key retrieval and verification commands.

The task stages six binary publications and the Gradle plugin marker, validates required artifacts and POM
metadata, checks signature files and checksums, then produces:

```text
build/graphmaquina-central-bundle.zip
```

Central staging requires the signing key. The task itself uploads nothing: the release script does that, or a
maintainer can upload the ZIP through Central Portal with the authorized namespace and release credentials.
Portal validation and public availability remain separate steps. The local structural validator does not
replace cryptographic signature verification or Portal checks.

Do not publish a bundle signed with a temporary test key. See the
[release preparation reference](typed-annotations-build.md#preparing-a-central-release) for details.

## Further reference

| Reference | What to use it for |
| --- | --- |
| [Migrating to 2.0](migration-2.0.md) | Every retired 1.x contract and its replacement, with before/after examples |
| [Testing guide](testing.md) | Typed assertions, probes, goldens and structural snapshots |
| [Build integration guide](typed-annotations-build.md) | Adapter lifecycle, toolchain checks, paths and repository configuration |
| [Shop source](../graphmaquina-example/src/main/java/io/github/graphmaquina/example) | Complete two-machine domain with async payment, guards, cascades, watches, after-commit hooks and the call's flow |
| [Shop tests](../graphmaquina-example/src/test/java/io/github/graphmaquina/example) | Executable behavior and expected diagrams |
| [Billing source](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/billing) | One payment machine reused as a sub-machine, in two states of a booking and in one of a subscription |
| [Billing tests](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/billing) | Starts and exits, a detached payment, an exit's failure route, an asynchronous exit and a sub-machine over its parent's own entity |
| [Public API source](../graphmaquina-core/src/main/java/io/github/graphmaquina/api) | Exact signatures and outcome types |
| [Programmatic DSL source](../graphmaquina-core/src/main/java/io/github/graphmaquina/dsl) | Lower-level builder stages |

## License

GraphMaquina is licensed under Apache License 2.0. See [LICENSE](../LICENSE) and [NOTICE](../NOTICE).

The code emitted into a consuming project by this project's generator and annotation processor belongs to
that project. The repository's license notice states that its license does not extend to that generated output.
