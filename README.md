# GraphMaquina

![GraphMaquina: typed graphs and predictable transitions, illustrated by a ticket moving from OPEN through IN_PROGRESS to DONE or FAILED.](docs/images/graphmaquina-overview.svg)

**Typed, graph-based state machines for Java 21.** Declare the transitions your domain allows, give every
forbidden transition a reason, and let the build validate the complete graph before the application runs.

GraphMaquina combines a pre-compilation code generator, a javac annotation processor and an in-process runtime.
Your application calls a generated, typed facade. Actions, guards, hooks, properties and watched states are
bound through Java symbols rather than string names.

**Current release: `1.0.0`, published to Maven Central under `io.github.graphmaquina`.**
Use the Gradle or Maven configuration below; cloning this repository and installing artifacts locally are
not required to consume the release. The source repository is temporarily private while maintainer testing
continues. Published Maven Central artifacts, including source JARs, remain public.

The project was previously named StateGraph. Some public Java names, such as `@StateGraphs`, and Gradle task
names retain `StateGraph`; use the exact names documented here.

This README is the developer guide for the current implementation. Complete, runnable files are provided in
the quickstart. Later examples are identified as extensions or fragments of the repository's shop example.

## Contents

- [What GraphMaquina provides](#what-graphmaquina-provides)
- [Requirements and artifacts](#requirements-and-artifacts)
- [Publication and verification status](#publication-and-verification-status)
- [Install the library locally](#install-the-library-locally)
- [Configure Gradle](#configure-gradle)
- [Configure Maven](#configure-maven)
- [Quickstart: a complete ticket machine](#quickstart-a-complete-ticket-machine)
- [Understand the transition matrix](#understand-the-transition-matrix)
- [Annotation and binding reference](#annotation-and-binding-reference)
- [Actions, guards and failure routes](#actions-guards-and-failure-routes)
- [Handle transition outcomes](#handle-transition-outcomes)
- [Asynchronous work and timeouts](#asynchronous-work-and-timeouts)
- [Build and manage machine sets](#build-and-manage-machine-sets)
- [Read peers and cascade transitions](#read-peers-and-cascade-transitions)
- [Watch other machines](#watch-other-machines)
- [Commit, hooks and listeners](#commit-hooks-and-listeners)
- [Concurrency, identity and persistence](#concurrency-identity-and-persistence)
- [Inspect graphs and export diagrams](#inspect-graphs-and-export-diagrams)
- [Test your machines](#test-your-machines)
- [Generation lifecycle and IDE setup](#generation-lifecycle-and-ide-setup)
- [Multiple modules and published JARs](#multiple-modules-and-published-jars)
- [Programmatic DSL](#programmatic-dsl)
- [Diagnostics and troubleshooting](#diagnostics-and-troubleshooting)
- [Migration from StateGraph](#migration-from-stategraph)
- [Known limitations](#known-limitations)
- [Build, verify and release this repository](#build-verify-and-release-this-repository)
- [Further reference](#further-reference)
- [License](#license)

## What GraphMaquina provides

| Capability | How it works |
| --- | --- |
| Exhaustive transition definitions | Every pair of states has an `ACTION`, `MOVE`, `STAY` or `FORBID` cell. Undeclared transitions are closed explicitly with a reason. |
| Compile-time structural validation | Checks include reachability, terminal states, intentional cycles, duplicate cells, failure routes and watch destinations. |
| Typed operations | A machine interface exposes domain operations such as `start(ticket)` or `pay(order)`, returning `Outcome<E, S>`. |
| Explicit bindings | Generated `Refs` classes identify marked methods and properties; the processor checks their roles and signatures. |
| Synchronous and asynchronous actions | Actions and guards may return values or `CompletionStage` results. The runtime waits under configured deadlines. |
| Coordinated state changes | Cascades share a unit of work; entity state setters run after the root transition succeeds or selects a handled failure route. |
| Declared watches | After a peer commits, a matching owner can be reevaluated using ordered rules. |
| Inspectable execution | Outcomes carry transition records and watch reactions; technical aborts carry diagnostic details where applicable. |
| Graph inspection | Render the complete matrix or export a deterministic Mermaid state diagram. |

GraphMaquina manages state transitions in objects you supply. It does not provide a database, entity locking,
a durable job queue, HTTP endpoints or distributed transactions. See [persistence and concurrency](#concurrency-identity-and-persistence)
for the responsibilities that remain with your application.

The build and runtime have separate jobs:

![Build pipeline: declare Java entities and machines, generate vocabulary and a manifest, compile and validate facades, then publish accepted exports. At runtime the application uses the typed facade and core.](docs/images/build-pipeline.svg)

## Requirements and artifacts

- **JDK 21 or later**, including `javac`, for generation and compilation. Use Java 21 as the baseline unless
  your application deliberately targets a newer release.
- The repository uses **Gradle 9.2.1** through its wrapper. This is the verified Gradle version; the project
  does not declare a broader tested compatibility matrix.
- Maven consumers need Maven and a Java 21 JDK. The complete configuration below uses
  **maven-compiler-plugin 3.15.0**, matching the repository's integration fixtures.
- **Lombok is optional for consumer entities and action classes.** The quickstart uses handwritten accessors.
  If you use Lombok, place its annotation processor before GraphMaquina's.
- Keep the runtime, generator, processor and adapter on the **same GraphMaquina version**.

| Module | Coordinates / plugin ID | Add it to |
| --- | --- | --- |
| Runtime and public annotations | `io.github.graphmaquina:graphmaquina-core:1.0.0` | Application dependencies |
| Pre-compilation generator | `io.github.graphmaquina:graphmaquina-codegen:1.0.0` | Build tooling; resolved by the adapter |
| Annotation processor | `io.github.graphmaquina:graphmaquina-processor:1.0.0` | javac annotation-processor path |
| Gradle plugin | `io.github.graphmaquina`, version `1.0.0` | Gradle `plugins` block |
| Maven plugin | `io.github.graphmaquina:graphmaquina-maven-plugin:1.0.0` | Maven build plugins |
| Reference application | `graphmaquina-example` | Source and tests in this repository; not an application dependency |

The generator and processor are build-time tools. Do not add them as application runtime dependencies.
Application imports begin with `io.github.graphmaquina`.

## Publication and verification status

GraphMaquina `1.0.0` is available from Maven Central. The namespace `io.github.graphmaquina` is verified.
The release contains the runtime, generator, processor, Maven plugin, Gradle plugin and Gradle plugin marker.

| Check | Verified result |
| --- | --- |
| Public availability | All six publication POMs return HTTP 200 from Maven Central. |
| Binary integrity | All five downloaded JARs match the SHA-256 hashes of the verified release bundle. |
| Release signatures | All 26 detached signatures were verified with the public key retrieved from the keyserver. |
| Automated library/build suites | 444 tests, with no failures, errors or skipped tests in the release verification run. |
| Published-release quickstart | The examples below compile and run with Gradle using Central without `mavenLocal()`, and with Maven using an initially empty local repository. Both report `All ticket scenarios passed.` |
| Remaining acceptance work | Manual IntelliJ acceptance and full strict-lint acceptance of the external payments demo remain pending. |

Browse the [published artifacts](https://repo.maven.apache.org/maven2/io/github/graphmaquina/) or the
[core release on Maven Central](https://central.sonatype.com/artifact/io.github.graphmaquina/graphmaquina-core/1.0.0).
MVNRepository is an independent index; appearing in its search results is not required for Maven or Gradle
resolution. Sonatype does not control its update schedule; see the
[official explanation](https://central.sonatype.org/faq/mvnrepository/).

The signing identity is `Hugo G <60438622+0xhug0@users.noreply.github.com>`. The public fingerprint is:

```text
851F 7594 9D70 884A C95A AB56 455A D78F 0EC0 B9F1
```

This fingerprint and the public key allow consumers to verify signatures; they do not permit signing a
release. Private signing material is stored in 1Password. See [release signing](docs/release-signing.md)
for verification commands and maintainer setup. Publishing a release does not resolve the
[known limitations](#known-limitations).

## Install the library locally

Local installation is optional and intended for testing changes to GraphMaquina itself. Released consumers
can skip to [Configure Gradle](#configure-gradle) or [Configure Maven](#configure-maven). While the repository
is private, cloning requires authorized GitHub access. Run these commands in a checkout of GraphMaquina:

```bash
git clone https://github.com/graphmaquina/graphmaquina.git
cd graphmaquina
java -version
./gradlew --version

./gradlew :graphmaquina-core:publishToMavenLocal \
          :graphmaquina-codegen:publishToMavenLocal \
          :graphmaquina-processor:publishToMavenLocal \
          :graphmaquina-maven-plugin:publishToMavenLocal

./gradlew -p graphmaquina-gradle-plugin publishToMavenLocal
```

The last command publishes both the Gradle plugin implementation and its plugin marker from the separate
included build. Both are needed for `id("io.github.graphmaquina") version "1.0.0"` to resolve.

On Windows, use `gradlew.bat` and the line-continuation syntax of your shell. The multi-line shell examples in
this guide use a POSIX shell.

Maven reads the local Maven repository by default. To test locally installed builds with Gradle, add
`mavenLocal()` before `mavenCentral()` in both `pluginManagement.repositories` and the dependency repository
configuration. Remove it when verifying the public release so a local build cannot shadow that release.
For an internal repository, configure both kinds of repository as well;
see [the repository configuration reference](docs/typed-annotations-build.md#where-the-artifacts-come-from).

## Configure Gradle

The following files build and run the [ticket quickstart](#quickstart-a-complete-ticket-machine). Create a
separate application directory, `tickets-demo`, and put these files at its root.

### settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tickets-demo"
```

Keep `mavenCentral()` inside both repository blocks: the plugin implementation and marker are published to
Central, along with the runtime and build tools. `gradlePluginPortal()` is included for other plugins; this
guide does not require a separate GraphMaquina publication to the Gradle Plugin Portal.

### build.gradle.kts

```kotlin
plugins {
    java
    application
    id("io.github.graphmaquina") version "1.0.0"
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("io.github.graphmaquina:graphmaquina-core:1.0.0")
    annotationProcessor("io.github.graphmaquina:graphmaquina-processor:1.0.0")
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

Add these dependency declarations **before** the GraphMaquina processor declaration:

```kotlin
compileOnly("org.projectlombok:lombok:1.18.48")
annotationProcessor("org.projectlombok:lombok:1.18.48")
annotationProcessor("io.github.graphmaquina:graphmaquina-processor:1.0.0")
```

Do not duplicate the GraphMaquina entry already present in the build. Lombok supplies accessors; it does not
replace GraphMaquina's generator or annotation processor.

### Source sets and generator selection

Generation participates in a source set only when that source set declares the GraphMaquina processor. Main
uses `annotationProcessor`; test uses `testAnnotationProcessor`. Tests that only call already generated main
facades normally do not need their own processor.

For graph declarations under `src/test/java`, add:

```kotlin
dependencies {
    testAnnotationProcessor("io.github.graphmaquina:graphmaquina-processor:1.0.0")
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
    generatorVersion.set("1.0.0")
}
```

An explicit dependency on `graphmaquinaGenerator` replaces the default dependency. The repository example uses
this to run the sibling codegen project. Version mixing is not a migration mechanism.

## Configure Maven

This is a complete alternative build for the same ticket source files. Save it as `pom.xml` in
`tickets-demo`. It includes **all six lifecycle executions** and distinct main/test manifest arguments.

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
    <graphmaquina.version>1.0.0</graphmaquina.version>
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
                <arg>-Xlint:all,-serial</arg>
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

Run from the application directory:

```bash
mvn --version
mvn clean verify
```

To run the quickstart after Maven has resolved the library and compiled the application, use the compiled
application classes and core JAR on the classpath:

```bash
java -cp "target/classes:$HOME/.m2/repository/io/github/graphmaquina/graphmaquina-core/1.0.0/graphmaquina-core-1.0.0.jar" \
     com.example.tickets.App
```

This command assumes Maven's default local repository. Adjust the path if your settings use another location;
on Windows, use `;` as the Java classpath separator.

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
`-proc:none`, missing processor configuration, divergent main/test manifests and skipped compilation such as
`maven.main.skip` or `maven.test.skip`. Run the normal lifecycle when generating graphs.

| Optional property | Meaning |
| --- | --- |
| `graphmaquina.javaExecutable` | Explicit Java executable for generation; it must agree with the compiler JDK |
| `graphmaquina.compilationId` | Override compilation identity; defaults distinguish artifact and main/test scope |

See the [Maven adapter reference](docs/typed-annotations-build.md#maven) for toolchains and custom compiler paths.

## Quickstart: a complete ticket machine

Create the following six files under `src/main/java/com/example/tickets/`. They are shared by the Gradle and
Maven configurations above and require no Lombok, framework or external service.

The workflow is `OPEN → IN_PROGRESS → DONE`, with a handled completion failure leading to `FAILED`.

![Ticket workflow: start moves OPEN to IN_PROGRESS if hasTitle passes; complete commits DONE on success or FAILED through onFailure. Rejection leaves the source state unchanged.](docs/images/ticket-workflow.svg)

### TicketStatus.java

```java
package com.example.tickets;

public enum TicketStatus {
    OPEN, IN_PROGRESS, DONE, FAILED
}
```

### Ticket.java

```java
package com.example.tickets;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Objects;

public final class Ticket {
    @GraphProperty
    private final String id;

    @GraphProperty
    private TicketStatus status = TicketStatus.OPEN;

    private final String title;
    private final boolean approved;

    public Ticket(String id, String title, boolean approved) {
        this.id = Objects.requireNonNull(id);
        this.title = Objects.requireNonNull(title);
        this.approved = approved;
    }

    public String getId() { return id; }
    public TicketStatus getStatus() { return status; }
    public String getTitle() { return title; }
    public boolean isApproved() { return approved; }

    public void setStatus(TicketStatus status) {
        this.status = Objects.requireNonNull(status);
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
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;

public final class TicketActions {
    @GraphGuard
    public GuardResult hasTitle(GuardContext<Ticket, TicketStatus> ctx) {
        return ctx.entity().getTitle().isBlank()
                ? GuardResult.reject("a ticket needs a title")
                : GuardResult.pass();
    }

    @GraphAction
    public ActionOutcome complete(TransitionContext<Ticket, TicketStatus> ctx) {
        return ctx.entity().isApproved()
                ? ActionOutcome.ok()
                : ActionOutcome.failed("approval was declined");
    }

    @GraphHook
    public void completed(Ticket ticket) {
        System.out.println("Committed " + ticket.getId() + " -> " + ticket.getStatus());
    }
}
```

### Tickets.java

```java
package com.example.tickets;

import static com.example.tickets.TicketStatus.DONE;
import static com.example.tickets.TicketStatus.FAILED;
import static com.example.tickets.TicketStatus.IN_PROGRESS;
import static com.example.tickets.TicketStatus.OPEN;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

@GenerateGraphApi
@TicketsGraph.Definition(initial = OPEN, terminals = {DONE, FAILED},
        state = TicketRefs.Status.class, id = TicketRefs.Id.class)
@TicketsGraph.Row(state = OPEN, otherwise = "start the ticket before completing it")
@TicketsGraph.Row(state = IN_PROGRESS, otherwise = "an active ticket must complete or fail")
@TicketsGraph.Cell(from = IN_PROGRESS, to = FAILED)
public interface Tickets extends Machine<Ticket, TicketStatus> {
    @TicketsGraph.Move(from = OPEN, to = IN_PROGRESS,
            guards = TicketActionsRefs.HasTitle.class)
    Outcome<Ticket, TicketStatus> start(Ticket ticket);

    @TicketsGraph.Action(from = IN_PROGRESS, to = DONE,
            action = TicketActionsRefs.Complete.class,
            onFailure = FAILED,
            afterCommit = TicketActionsRefs.Completed.class)
    Outcome<Ticket, TicketStatus> complete(Ticket ticket);
}
```

`TicketsGraph`, `TicketRefs` and `TicketActionsRefs` do not exist in handwritten source. The generator writes
them before javac compiles this interface. Do not create placeholder versions of these classes.

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

The processor generates `Tickets_` and `TicketMachines_`. The set owns the runtime machines and closes their
default executors together.

### App.java

```java
package com.example.tickets;

import io.github.graphmaquina.api.Outcome;

public final class App {
    private App() { }

    public static void main(String[] args) {
        try (TicketMachines machines = TicketMachines_.builder(new TicketActions()).build()) {
            Tickets tickets = machines.tickets();

            Ticket accepted = new Ticket("T-1", "Document the workflow", true);
            require(tickets.start(accepted) instanceof Outcome.Applied<?, ?>, "start should apply");
            require(tickets.complete(accepted) instanceof Outcome.Applied<?, ?>, "complete should apply");
            require(accepted.getStatus() == TicketStatus.DONE, "successful ticket should be DONE");

            Ticket invalid = new Ticket("T-2", "", true);
            require(tickets.start(invalid) instanceof Outcome.Rejected<?, ?>, "blank title should reject");
            require(invalid.getStatus() == TicketStatus.OPEN, "rejected ticket should remain OPEN");

            Ticket declined = new Ticket("T-3", "Await approval", false);
            tickets.start(declined);
            require(tickets.complete(declined) instanceof Outcome.Failed<?, ?>, "decline should be handled");
            require(declined.getStatus() == TicketStatus.FAILED, "failure route should commit FAILED");

            require(tickets.complete(new Ticket("T-4", "Not started", true))
                    instanceof Outcome.Rejected<?, ?>, "completion from OPEN should be forbidden");
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
Committed T-1 -> DONE
All ticket scenarios passed.
```

The success hook runs only on the committed success edge. The declined action commits the internal failure
cell instead. The program checks successful transitions, a rejecting guard, a handled failure and a forbidden
operation; it does not rely on Java assertions being enabled.

## Understand the transition matrix

With `N` enum constants, the graph has `N × N` cells. Rows represent the current state; columns represent the
requested destination. The ticket example resolves to this matrix:

![All sixteen ticket transition cells: STAY on the diagonal, MOVE from OPEN to IN_PROGRESS and from IN_PROGRESS to FAILED, ACTION from IN_PROGRESS to DONE, and FORBID in every other cell.](docs/images/transition-matrix.svg)

| From / to | OPEN | IN_PROGRESS | DONE | FAILED |
| --- | --- | --- | --- | --- |
| OPEN | STAY | MOVE + `hasTitle` | FORBID | FORBID |
| IN_PROGRESS | FORBID | STAY | ACTION `complete` | MOVE, internal failure route |
| DONE | FORBID | FORBID | STAY | FORBID |
| FAILED | FORBID | FORBID | FORBID | STAY |

| Cell kind | Runtime behavior |
| --- | --- |
| `ACTION` | Evaluate guards, run the action, then commit its destination or its handled failure route. |
| `MOVE` | Evaluate guards and commit the destination without running an action. |
| `STAY` | Return `Outcome.Ignored`; no state change. The typed annotation API supplies the diagonal automatically. |
| `FORBID` | Return `Outcome.Rejected` with the declared reason; no guards or actions run. |

Every nonterminal state must have a `@<Machine>Graph.Row` with a nonblank `otherwise` reason. That reason closes
its undeclared off-diagonal cells. Declared actions, moves, internal cells and explicit forbids take their
respective cells; a row default does not override them. Terminal rows are closed automatically, but can declare
an explicit row to customize their reasons.

Use `@<Machine>Graph.Forbid(from = ..., to = ..., reason = "...")` for a particular forbidden cell whose
explanation differs from the row default. Two explicit declarations cannot claim the same cell.

### Structural rules

- All enum constants participate, and every state must be reachable from `initial` through actions or moves.
- A terminal has no action or move exit to another state. A state without an exit must be terminal.
- The diagonal is `STAY`. An action or move cannot declare the same state in `from` and `to`.
- Every cycle must intersect at least one state listed in `allowsCycle`. An allowance for a state that is not
  part of any cycle is rejected too.
- An `onFailure` destination must have a separately declared action or move from the **original source state**.
- Every watch destination must also be an action or move from the watched state. A terminal cannot watch.

For example, the shop permits `NEW → PAYMENT_DECLINED → NEW` by declaring
`allowsCycle = PAYMENT_DECLINED`. This documents an intentional retry loop; guards still decide when another
retry is permitted.

### Operations and internal cells

A public operation has exactly this shape:

```java
Outcome<Ticket, TicketStatus> complete(Ticket ticket);
```

It carries one action or move annotation from its own generated vocabulary. The generated implementation
requests that annotation's destination from the entity's current state. The matrix decides what happens from
that current state; `from` declares graph cells, rather than adding a separate imperative precondition inside
the Java method. Avoid assigning the same target to operations that you expect to mean different things: the
runtime selects behavior by `(current state, target state)`.

`@Cell` declares an internal transition without adding a facade method. It is useful for failure routes,
cascades and watches. Omitting its `action` makes it a move. A typed facade intentionally exposes no generic
`transitionTo`: application entry points are the operations you declared, plus `reevaluate`.

`allowedFrom(state)` returns graph destinations with action or move cells, including internal cells. It does
**not** run guards or predict whether a particular entity will succeed.

## Annotation and binding reference

### Handwritten markers

Import these from `io.github.graphmaquina.annotation`:

| Annotation | Place it on | Purpose |
| --- | --- | --- |
| `@GenerateGraphApi` | Top-level `Machine<E, S>` interface | Generate its typed graph vocabulary |
| `@StateGraphs` | Top-level interface extending `Machines` | Generate a set and builder for its machine accessors |
| `@GraphProperty` | Entity field or getter | Export a property symbol |
| `@GraphAction` | Action method | Export an action binding |
| `@GraphGuard` | Guard method | Export a guard binding |
| `@GraphHook` | Hook method | Export an after-commit binding |
| `@GraphCondition` | Watch-condition method | Export a custom watch predicate |
| `@GraphBindings` | Concrete binding host | Export inherited marked members through this concrete host |
| `@GraphPeers` | Watching machine interface | Request typed vocabulary for its declared owner/peer pairs |
| `@Timeout` | Nested annotation element | Express seconds and nanoseconds for an asynchronous action timeout |

Markers identify exports; marking a method alone does not attach it to a transition. Reference its generated
symbol from the graph declaration.

### Generated names

| Handwritten declaration | Generated API |
| --- | --- |
| `Tickets extends Machine<Ticket, TicketStatus>` | `TicketsGraph.Definition`, `.Row`, `.Action`, `.Move`, `.Cell`, `.Forbid` |
| Property `Ticket.status` | `TicketRefs.Status.class` |
| Marked method `TicketActions.hasTitle` | `TicketActionsRefs.HasTitle.class` |
| `@GraphPeers(Items.class)` on `Orders` | `OrdersItems.Watch` and `OrdersItems.When`, in the owner's package |
| Machine `Tickets` | `Tickets_`, including `KEY`, graph construction and facade implementation |
| Set `TicketMachines` | `TicketMachines_`, including the generated builder |

These are conventional examples. A leading lowercase letter becomes uppercase (`charge` → `Charge`);
other leading characters get an underscore (`URL` → `_URL`, `_id` → `__id`). A name colliding with its own
container gets an `_sg_` prefix, and nested hosts join enclosing names with underscores (`Outer.Inner` →
`Outer_InnerRefs`). Use the generated source or IDE completion instead of guessing escaped spellings.
See [symbol naming](docs/typed-annotations-migration.md#7-how-generated-symbols-are-named).

A generated symbol is a compile-time reference. It is not an action instance, a dependency-injection bean or
a reflective lookup. The facade calls the selected declaration directly, preserving the marked overload.

### Generated graph annotation elements

In this table, `S` is the machine's state enum and `P` is a property symbol class.

| Annotation | Required elements | Optional elements |
| --- | --- | --- |
| `Definition` | `initial: S`, `state: P`, `id: P` | `terminals: S[]`, `allowsCycle: S[]` |
| `Row` | `state: S`, `otherwise: String` | None |
| `Action` | `from: S[]`, `to: S`, `action: ActionSymbol` | `guards`, `onFailure`, `failsOn`, `timeout`, `afterCommit` |
| `Move` | `from: S[]`, `to: S` | `guards`, `afterCommit` |
| `Cell` | `from: S[]`, `to: S` | `action`, `guards`, `onFailure`, `failsOn`, `timeout`, `afterCommit` |
| `Forbid` | `from: S`, `to: S`, `reason: String` | None |

Rows, internal cells and forbids are repeatable. `from` must contain at least one state. Guard arrays execute
in declaration order. `failsOn` takes exception class literals.

Java annotations do not have nullable elements, so the API represents optional single values as arrays.
`onFailure`, `timeout`, `afterCommit`, `Cell.action` and custom `When.condition` accept **zero or one** value.
Use the single-value syntax, for example `onFailure = FAILED`, or omit the element. Multiple values are `SG607`.

### Properties and accessors

For a field marked `@GraphProperty`, the processor looks for public instance readers in this order:
`property()`, `getProperty()`, `isProperty()`. State writers are searched as `setProperty(value)` and then
`property(value)`. A field marker does not grant access to the field and does not generate an accessor.

A marker on a getter selects that getter directly. Its logical property name follows JavaBeans
normalization: `getStatus()` exports `status`, `items()` exports `items`, and `getURL()` exports `URL`.

| Property use | Required shape |
| --- | --- |
| `Definition.state` | Public instance reader returning the machine's state enum; compatible public instance writer |
| `Definition.id` | Public instance reader returning an identity value; values must be non-null at runtime |
| `Watch.peers` | Owner reader returning `Collection<? extends PeerEntity>` |
| `Watch.owner` | Peer reader returning `Optional<OwnerEntity>` or an owner entity |

Use `Optional.empty()` for a peer with no owner. Keep relationship accessors consistent and return non-null
collections. If the setter overloads are ambiguous, the processor reports the ambiguity instead of choosing
based on declaration order.

### Method signatures

Public methods must match the role where the graph uses them. `E/S` refer to the owner entity/state types;
`P/Q` refer to the peer entity/state types.

| Marker | Supported signature |
| --- | --- |
| `@GraphAction` | `ActionOutcome method(TransitionContext<E, S> ctx)` |
| `@GraphAction`, async | `CompletionStage<ActionOutcome> method(TransitionContext<E, S> ctx)` |
| `@GraphGuard` | `GuardResult method(GuardContext<E, S> ctx)` |
| `@GraphGuard`, async | `CompletionStage<GuardResult> method(GuardContext<E, S> ctx)` |
| `@GraphHook` | `void method(E entity)` |
| `@GraphCondition` | `boolean method(E owner, Collection<? extends P> peers, PeerView<P, Q> view)` |

Static bindings are called statically. Instance bindings become constructor/builder dependencies. For example,
using three instance members of `OrderActions` adds one `OrderActions` dependency, not three instances. A class
used only through static members needs no builder argument.

To expose inherited marked members under a concrete host, use `@GraphBindings` on that host. The processor
checks the inherited signature with the concrete generic arguments substituted. Generated output and JAR
exports are checked for collisions; do not manually create classes in generated namespaces or edit symbols.

## Actions, guards and failure routes

The ticket quickstart shows a synchronous action and guard. Actions receive `TransitionContext<E, S>`:

| Method | Meaning |
| --- | --- |
| `entity()` | The object supplied by the caller |
| `from()` / `to()` | Source state and requested destination of this transition |
| `depth()` | `0` for the root, increasing through cascades |
| `peer(...)` | Read another registered machine through a `PeerView` |
| `cascade(...)` | Request a peer transition within this unit of work |
| `cascadeAll(...)` | Apply a cascade to a collection using the selected policy |

Guards receive `GuardContext<E, S>`, which exposes the entity, source, target and read-only peer access.
It has no cascade API. Return `GuardResult.pass()` or `GuardResult.reject("reason")`; do not return null.
A rejecting guard stops the transition before its action runs. Guard code should be free of mutations and
external side effects, because a guard is a decision rather than a commit step.

### Domain failures

An action returns `ActionOutcome.ok()` or `ActionOutcome.failed("reason")`. A failed action is a handled
business result only when the edge has an `onFailure` destination. Without one, the runtime throws
`UnhandledFailureException` and does not commit the planned state changes.

A failure route is declared from the original source, as in the ticket example:

```java
@TicketsGraph.Cell(from = IN_PROGRESS, to = FAILED)
```

When completion fails, the runtime discards the success edge's pending changes and selects
`IN_PROGRESS → FAILED`. It returns `Outcome.Failed`, and the entity is now `FAILED`.

The failure route can itself be an action. Its guards are bypassed when entered as failure handling, and its
own failure route is not chained. A failure or exception from that route aborts. Hooks apply to the edge that
actually committed, so a success-edge hook does not run for its failure route.

### Map specific exceptions to a domain failure

This declaration is an excerpt from the shop's `Orders` machine:

```java
@OrdersGraph.Action(from = NEW, to = PAID,
        action = OrderActionsRefs.Charge.class,
        guards = OrderActionsRefs.RetryBudgetLeft.class,
        onFailure = PAYMENT_DECLINED,
        failsOn = IOException.class,
        timeout = @Timeout(seconds = 5))
Outcome<Order, OrderStatus> pay(Order order);
```

The corresponding async action calls a gateway and returns a stage. An exceptional completion with a matching
`IOException` uses the declared failure route. The runtime unwraps completion/execution wrappers before
classifying their cause. `failsOn` applies to action failures; a guard exception is a technical abort.

`failsOn` cannot be a blanket `Throwable`, `Exception` or `RuntimeException`, an `Error`, or a library engine
exception. Declare specific foreign exception types. Timeout, interruption and cancellation are not a
substitute for a returned domain failure. `@Timeout` is valid only for asynchronous actions.

The full action implementation is in
[OrderActions.java](graphmaquina-example/src/main/java/io/github/graphmaquina/example/order/OrderActions.java).

## Handle transition outcomes

`Outcome<E, S>` is a sealed interface. Use its variants to distinguish business results:

| Variant | State changes? | Useful fields |
| --- | --- | --- |
| `Applied` | Yes, the requested edge committed | `from`, `to`, `records`, `reactions` |
| `Ignored` | No | `from`, `to`; stay, convergent no-op or reevaluation without a match |
| `Rejected` | No | `reason`, optional rejecting `guard`, requested `to` |
| `Failed` | Yes, the handled failure route committed | requested `to`, actual `movedTo`, `reason`, optional `cause`, `records`, `reactions` |

Every outcome has `entity()`, `from()` and `current()`. For `Failed`, `current()` is `movedTo()`, not the
requested destination. Outcomes describe that call's result; later watch reactions or subsequent operations
can move an entity again. Read the entity when you need its live state.

This method can be added to the quickstart's `App`:

```java
static String describe(Outcome<Ticket, TicketStatus> outcome) {
    return switch (outcome) {
        case Outcome.Applied<Ticket, TicketStatus> applied -> "Moved to " + applied.to();
        case Outcome.Ignored<Ticket, TicketStatus> ignored -> "Unchanged at " + ignored.current();
        case Outcome.Rejected<Ticket, TicketStatus> rejected -> "Rejected: " + rejected.reason();
        case Outcome.Failed<Ticket, TicketStatus> failed ->
                "Moved to " + failed.movedTo() + ": " + failed.reason();
    };
}
```

Technical failures throw instead of becoming a fifth outcome. Inspect the exception type and phase before
retrying: after-commit failures occur after state was already written.

### Records, journals and reactions

- `TransitionRecord` describes a planned-and-committed hop: machine key, entity, source, destination, cell
  kind, action label, cascade depth and whether it was a convergent no-op.
- Root `Applied` and `Failed` outcomes include the transaction's records. A nested `cascade` result is
  provisional and has no independent root commit or reaction list.
- An abort journal uses `ActionRecord` and `ActionStatus` (`RUNNING`, `OK`, `FAILED`, `THREW`) to report actions
  that actually ran. A journal is execution evidence, not an undo log for external side effects.
- A watch produces `Reaction.Done`, carrying its own outcome, or `Reaction.Aborted`, carrying its exception.
  Inspect reactions even when the root operation succeeded.

## Asynchronous work and timeouts

An asynchronous action has the same context as a synchronous one but returns a `CompletionStage<ActionOutcome>`.
An asynchronous guard returns `CompletionStage<GuardResult>`. The shop's payment action demonstrates a real
asynchronous service boundary through its `PaymentGateway` interface.

This method belongs to the shop's `OrderActions`, whose `gateway` is a constructor dependency:

```java
@GraphAction
public CompletionStage<ActionOutcome> charge(TransitionContext<Order, OrderStatus> ctx) {
    ctx.entity().countAttempt();
    return gateway.chargeAsync(ctx.entity()).thenApply((Charge charge) ->
            charge.declined() ? ActionOutcome.failed("declined: " + charge.code()) : ActionOutcome.ok());
}
```

The callback converts the service result to a domain outcome. Context `peer`, `cascade`, `cascadeAll` and
context-derived peer views are confined to the thread executing the original action or guard, even while
the stage is pending. Do not call them from a `thenApplyAsync` callback or another completion thread; the
runtime detects this with `java.lang.WrongThreadException`. Capture any required immutable input before
starting the asynchronous work. Context lifetime and application entity thread safety are separate constraints.

There are two different choices:

1. **Async work inside an operation:** an action or guard returns a stage. Calling the facade operation
   normally still blocks while the engine waits for that stage.
2. **Move the whole operation off the caller thread:** use `machine.async(() -> machine.operation(entity))`.

For example, with an existing `ShopMachines shop` and `Order order`:

```java
CompletableFuture<Outcome<Order, OrderStatus>> pending =
        shop.orders().async(() -> shop.orders().pay(order));

pending.thenAccept(outcome -> System.out.println(outcome.current()));
```

Import `java.util.concurrent.CompletableFuture`. Keep the machine set alive until submitted work completes.
`async` does not make concurrent changes to the same entity safe.

### Deadline rules

A generated set containing asynchronous bindings requires a default `Duration` as the first builder argument.
For the shop:

```java
ShopMachines shop = ShopMachines_.builder(
        Duration.ofSeconds(10), new OrderActions(gateway, carrier), new ItemActions())
        .asyncTimeout(Orders.class, Duration.ofSeconds(8))
        .build();
```

The per-machine override is accepted only for an asynchronous machine in that set. Durations must be positive.
For synchronous-only sets, such as `TicketMachines`, the builder has no timeout argument.

The root timeout establishes a deadline shared by nested work. An action-specific
`@Timeout(seconds = 5)` can shorten the wait for that action; it cannot extend the root deadline. Async guards
use the root deadline. Synchronous code runs inline; these timeouts do not preempt a blocking synchronous call.

The set default is installed only on machines with declared asynchronous bindings. A synchronous-only root
does not acquire a deadline merely because it cascades into an asynchronous peer, and the peer's machine-level
timeout does not replace that root's deadline. In that arrangement, use a timeout on the peer's asynchronous
action to bound that action's wait; async guards still need a bounded root. Do not assume the set's `Duration`
is a universal deadline for every cascade originating in the set.

On timeout or interruption, waiting aborts and the context becomes invalid. The library leaves the user's
stage running; it does not cancel a remote request. Do not retain contexts for use after the operation ends.
A late callback can still perform its own external side effects, so service cancellation and idempotency need
an application-level design.

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
the set's declarations; inspect the generated method or completion when dependencies change. GraphMaquina does
not instantiate service classes or discover dependency-injection beans.

| Builder option | Purpose / default |
| --- | --- |
| `asyncTimeout(Machine.class, Duration)` | Override timeout for one asynchronous machine |
| `asyncExecutor(Executor)` | Supply the executor used by `async` calls; otherwise each machine owns a virtual-thread executor |
| `maxCascadeDepth(int)` | Bound nested cascades; default `10`, minimum `1`, root depth is `0` |
| `maxWatchChain(int)` | Bound successive watch reactions; default `10`, minimum `1` |
| `listener(TransitionListener)` | Observe outcomes across the set |
| `build()` | Construct the registry and facades |

Close the set with try-with-resources or your application's shutdown lifecycle. `close()` closes executors
owned by the set and waits for submitted operations to finish. An executor you supply remains your
responsibility. Avoid sharing a short-lived set across requests that outlive its scope.

For dependency injection, register the generated set as an application-managed component using your existing
`OrderActions` and `ItemActions` instances, then inject its facades into services. Arrange for `close()` at
shutdown. There is no required Spring dependency or GraphMaquina-specific container integration.

## Read peers and cascade transitions

Register interacting machines in the same set. Inside a guard or action, use the peer's **facade class** so
Java infers its entity and enum types:

```java
@GraphGuard
public GuardResult allItemsPacked(GuardContext<Order, OrderStatus> ctx) {
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
public ActionOutcome cancel(TransitionContext<Order, OrderStatus> ctx) {
    ctx.cascadeAll(Items.class, ctx.entity().items(), ItemStatus.CANCELLED,
            CascadePolicy.COLLECT);
    return ActionOutcome.ok();
}
```

For one entity, use `ctx.cascade(Items.class, item, ItemStatus.CANCELLED)`. Machine-key overloads also exist
for lower-level integrations.

A cascade runs the peer's normal matrix, guards and action. Its success stages a change; it does not commit
independently. The root commits after all required work succeeds. A peer already staged at the requested
state can converge to an ignored no-op; a conflicting in-flight request is a cascade cycle.

| Policy | Behavior |
| --- | --- |
| `FAIL_FAST` | Stop when a cascade fails. |
| `COLLECT` | Try peers under savepoints, aggregate transition-abort errors, then abort the whole root if any were collected. Other engine failures can abort immediately. |

`COLLECT` is not partial commit. A forbidden or guard-rejected nested transition is an abort, rather than an
ordinary root-level `Rejected` result. A handled domain failure can select the child's declared failure route.
An engine failure poisons the unit of work; catching it in your action does not make the transaction safe to
commit. Use `ctx.cascade` instead of calling another facade operation from inside an action: nested facade
entry points violate the runtime's reentrancy rules.

`@GraphPeers` is for generating **watch** vocabulary. A runtime peer read or cascade alone does not require an
unused watch-pair declaration.

## Watch other machines

A watch reevaluates an owner after one of its peers changes state. It declares both directions of the
relationship: owner → peer collection and peer → optional owner.

The shop has orders and items. An order watches its items while it is `PAID`, and ships when all are `PACKED`.

![After the last item commits PACKED, the PAID order is reevaluated in a new transaction. If all peers are PACKED, its normal shipping edge runs. A failed reaction does not undo the item commit.](docs/images/watch-reactions.svg)

This excerpt belongs on the shop's `Orders` interface, alongside its definition, rows and operations:

```java
@GraphPeers(Items.class)
@OrdersItems.Watch(
        state = PAID,
        peers = OrderRefs.Items.class,
        owner = ItemRefs.Order.class,
        rules = @OrdersItems.When(allIn = ItemStatus.PACKED, goTo = SHIPPED))
```

`OrdersItems` is generated from the pair. `goTo` is an `OrderStatus`; `allIn` is an `ItemStatus`. Mixing those
enums is a javac type error. The `PAID → SHIPPED` edge must exist and is executed normally, including its guard,
action and hook.

### Ordered rules

A watch has one or more rules, and the **first matching rule wins**. Each rule uses exactly one of:

| Rule element | Condition |
| --- | --- |
| `allIn = ItemStatus.PACKED` | Nonempty peers, all in the given state |
| `anyIn = ItemStatus.CANCELLED` | At least one peer in the given state |
| `noneIn = ItemStatus.PACKING` | No peer in the given state; true for empty peers |
| `condition = OrderConditionsRefs.Ready.class` | A marked custom predicate |

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
                         PeerView<Item, ItemStatus> view) {
        return !peers.isEmpty() && view.allIn(peers, ItemStatus.PACKED);
    }
}
```

Replace the built-in rule with
`@OrdersItems.When(condition = OrderConditionsRefs.Ready.class, goTo = SHIPPED)`. Regenerate and supply the new
`OrderConditions` instance to the resulting set builder. Custom conditions are synchronous predicates.

With this replacement in the reference shop, the generated builder becomes:

```java
ShopMachines shop = ShopMachines_.builder(
        Duration.ofSeconds(10), new OrderConditions(), new OrderActions(gateway, carrier), new ItemActions())
        .build();
```

Close this set as described in [machine lifecycle](#build-and-manage-machine-sets). Binding changes can change
the generated builder's parameter list and order; regenerate before updating the application's wiring.

A machine can have at most one watch per owner state. Multiple rules belong inside that watch. Declare only
pairs that are actually watched; duplicate or unused `@GraphPeers` entries are errors.

### Triggering and observing watches

After a root commit, the runtime finds watched owners from peers whose final state differs from their
original state. Intermediate hops that return a peer to its original state do not trigger dispatch. It reevaluates an owner only if it
is currently in the watched state and deduplicates owners within that dispatch. Each reaction is a **new root
transaction**. A reaction failure does not roll back the peer commit that triggered it.

Changing a collection or setting a field directly does not emit a transition event. If your application
changes relationships outside a peer transition, explicitly request reevaluation where appropriate:

```java
Outcome<Order, OrderStatus> outcome = shop.orders().reevaluate(order);
```

No watch or no matching rule produces `Outcome.Ignored`. A matching rule can still be rejected by the target
edge's guards. Rules are not polled on a timer, and creating or paying an owner does not automatically mean all
of its existing peers changed state.

**Cross-JAR watches are not supported yet.** The owner and peer APIs must be generated in the same compilation.
A peer in another module or dependency JAR is rejected with `SG608`. Track the enhancement in
[issue #1](https://github.com/graphmaquina/graphmaquina/issues/1).

## Commit, hooks and listeners

![Root actions and cascades stage changes in a shared unit of work. Commit checks original states and writes final states; hooks, independent watch reactions, listener notification and return follow on normal completion.](docs/images/commit-lifecycle.svg)

The runtime's normal root execution follows this sequence:

1. Read the entity state and resolve its matrix cell.
2. Evaluate guards and run actions, including any nested cascades, against a shared unit of work.
3. Before commit, verify that participating entities still have their expected original states.
4. Write each participating entity's final state through its setter.
5. Invalidate transition contexts and release the root reentrancy marker.
6. Run hooks for the committed hops, in log order.
7. Dispatch watch reactions for moved peers.
8. Notify the listener with this call's outcome and return it.

A rejected or ignored root does not commit state changes. A handled failure does commit its failure route.
External side effects performed by actions, and mutations to other entity fields, are not automatically undone
when the unit of work aborts.

### After-commit hooks

Use a `@GraphHook` when code must run after GraphMaquina has written state. The quickstart prints the completed
ticket from such a hook. The shop notifies its carrier after the shipping edge commits.

Hooks run synchronously on the operation's thread. A hook exception becomes `AfterCommitException`:
**the state is already committed**. The exception stops the remaining normal post-commit sequence, so do not
assume later hooks, watch dispatch or listener notification ran. Make externally visible notifications
idempotent and choose durable delivery separately if your application needs it.

A hook sees the entity's committed final state. When a cascade stages multiple hops for one entity, the setter
writes its final destination once, while hooks still follow committed hop order.

### Listeners

A listener receives each completed outcome, including ignored and rejected operations. With the ticket set:

```java
TicketMachines machines = TicketMachines_.builder(new TicketActions())
        .listener((key, outcome) -> System.out.println(
                key.entity().getSimpleName() + ": " + outcome.getClass().getSimpleName()
                        + " -> " + outcome.current()))
        .build();
```

Close `machines` through the application's lifecycle. A listener sees watch-reaction outcomes through their
own calls; do not recursively emit them again from the parent's `reactions()` list. Reaction outcomes can
arrive before the triggering root's notification. Technical aborts that never produce an outcome do not
become listener outcome events.

Listener exceptions also surface as `AfterCommitException`. For an ignored or rejected outcome, no state
change occurred; for an applied or handled-failure outcome, state has already committed. A listener exception
must not be interpreted as proof that an operation never happened.

## Concurrency, identity and persistence

### Initialize and protect state

The entity owns its state. Initialize it in the constructor or field declaration, and persist it with your
normal entity mapping. Null state is an `UninitializedStateException`; the runtime does not silently replace
it with the graph's initial state.

During a transition, only the runtime should call the state setter. It checks state again before commit to
catch action code or outside code that changed it unexpectedly. This check is not a lock: your application
must serialize conflicting operations, or provide an appropriate database locking/versioning strategy.

The runtime does not roll back a setter that already ran if a subsequent setter throws.
`CommitFailedException` reports applied and pending entities. Keep state setters simple and total for valid
states; avoid remote calls, persistence flushes and unrelated business logic inside them.

### Stable identity

An identity is scoped by the machine key, which contains the entity and state types. It must be stable,
non-null and suitable for identifying one domain entity. Using two different object instances with the same
machine identity in one unit of work is an `AmbiguousIdentityException`; this prevents silently treating two
representations of one row as separate entities.

When working with an ORM, load and reuse a consistent object instance for each identity within the operation.
Identity tracking does not provide database uniqueness enforcement or cross-process coordination.

### Framework and database boundaries

Create the set once at the lifecycle scope appropriate for your application and inject its action hosts with
existing repositories and services. Retrieve entities, invoke the facade, inspect the outcome and persist using
your application's transaction boundary.

**GraphMaquina's after-commit means its in-memory state commit.** It does not mean a surrounding database
transaction committed. If a message must follow a durable database commit, use your persistence framework's
transaction-completion facility or a transactional outbox rather than assuming the graph hook provides that
guarantee.

Executor-based calls do not automatically propagate thread-local transactions, security contexts or ORM
sessions. Arrange those boundaries on the execution thread. Async action callbacks must not use an entity or
persistence context concurrently without the application's own coordination.

## Inspect graphs and export diagrams

Every facade exposes its definition:

```java
System.out.println(tickets.definition().render());
System.out.println(tickets.allowedFrom(TicketStatus.OPEN));
```

`render()` shows the exhaustive matrix, including forbidden transitions and their reasons.
`definition().edge(from, to)` exposes the resolved cell, and `definition().graph()` exposes the graph of
moving edges. `initial()`, `terminals()`, `watch(state)` and `hasAsync()` are also available.

To export Mermaid from an existing ticket facade:

```java
import io.github.graphmaquina.export.Detail;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.MermaidExporter;

String diagram = MermaidExporter.render(GraphView.of(tickets.definition()));
String fullDiagram = MermaidExporter.render(GraphView.of(tickets.definition()), Detail.FULL);
System.out.println(diagram);
```

This is a fragment: put the imports at file scope and the statements inside a method. The default detail is
`TRANSITABLE`, showing action and move edges; `FULL` includes stay and forbid cells. Export does not run
transitions or evaluate guards.

You can also build a definition directly through `Tickets_.graph(new TicketActions())` without constructing a
running set.

[The illustrated ticket workflow](docs/images/ticket-workflow.svg) summarizes the moving edges and failure
route. The exporter produces its own labels from the definition. The repository keeps exact expected exports in
[the example's golden diagrams](graphmaquina-example/src/test/resources/golden).

The illustrations in this guide are maintained separately from the Mermaid exporter. Their accessible SVG
sources and regeneration instructions are in [docs/images](docs/images/README.md).

## Test your machines

Test through generated facades using real entities and deterministic action dependencies. The quickstart's
`App` is an executable smoke test, and
[OrderFlowTest](graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java)
shows JUnit tests for payment, retries, cascades, watches and journals.

For a Gradle application using JUnit, add:

```kotlin
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

Create `src/test/java/com/example/tickets/TicketsTest.java`:

```java
package com.example.tickets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.graphmaquina.api.Outcome;
import org.junit.jupiter.api.Test;

class TicketsTest {
    @Test
    void aDomainFailureCommitsItsDeclaredFailureState() {
        try (TicketMachines machines = TicketMachines_.builder(new TicketActions()).build()) {
            Ticket ticket = new Ticket("T-5", "Declined approval", false);
            assertInstanceOf(Outcome.Applied.class, machines.tickets().start(ticket));
            Outcome<Ticket, TicketStatus> result = machines.tickets().complete(ticket);
            assertInstanceOf(Outcome.Failed.class, result);
            assertEquals(TicketStatus.FAILED, result.current());
            assertEquals(TicketStatus.FAILED, ticket.getStatus());
        }
    }
}
```

Run `./gradlew test`. This test consumes generated main code; it does not declare test graphs and needs no
`testAnnotationProcessor`. In Maven, add JUnit and a compatible test runner through your application's usual
test configuration; preserve the graph generation lifecycle for any test graphs.

Useful behavior to cover in your own domain:

- Every action's success and handled-failure result, including the actual entity state.
- Each guard rejection and forbidden transition reason.
- Exceptions that are mapped through `failsOn`, and exceptions that must abort.
- Cascade rollback behavior and collected error details.
- First-match watch order, empty peer collections and aborted reactions.
- Async timeout behavior and late completions.
- Hooks and listeners that fail after state has committed.
- Exact exported diagrams when graph changes require review.

A passing runtime test does not replace compile-time graph validation. Conversely, a valid graph does not prove
that your action performs the correct domain operation.

## Generation lifecycle and IDE setup

GraphMaquina uses **pre-compilation generation**, because the annotations and symbols used by your source must
exist before javac can type-check that source:

```text
generate -> prepare-compile -> javac (Lombok if used, then GraphMaquina) -> complete-compile
```

The generator parses declarations and emits vocabulary plus a manifest. The processor verifies that the manifest
matches the declarations javac accepted, resolves bindings, checks the graph and emits facades. Completion
publishes the validated export index and records ownership of generated outputs.

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

Gradle registers generated vocabulary as a source-set root, so source archives and IDE imports can see it.
The plugin disables javac incremental compilation for the project's Java tasks so partial javac output does
not conflict with complete ownership inventories. Gradle task up-to-date checks and configuration-cache
support are separate; unchanged builds can still skip work.

### IDE workflow

1. Import the project through its Gradle or Maven model, using the intended Java 21 JDK.
2. Run `./gradlew compileJava` or `mvn compile` once to create vocabulary and facades.
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
   build error, not a reason to fall back to string bindings.

Support for published symbols and sets does **not** imply support for a watch whose pair spans separate
compilations. Keep watched owner and peer declarations in the same compilation until
[issue #1](https://github.com/graphmaquina/graphmaquina/issues/1) is implemented.

## Programmatic DSL

The typed annotation API is the main application workflow. The core artifact also includes a Java DSL under
`io.github.graphmaquina.dsl`, which constructs a `GraphDefinition` at runtime. Its graph validation happens
when `build()` runs, rather than during javac. The DSL's class name remains `StateGraph`.

The following complete utility can be added to the ticket quickstart package. It creates an alternative
programmatic definition using the same entity, without using its generated facade:

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

    public static Outcome<Ticket, TicketStatus> run(Ticket ticket) {
        MachineKey<Ticket, TicketStatus> key = MachineKey.of(Ticket.class, TicketStatus.class);
        GraphDefinition<Ticket, TicketStatus> graph = StateGraph.define(key)
                .initial(TicketStatus.OPEN)
                .terminal(TicketStatus.DONE)
                .terminal(TicketStatus.FAILED)
                .from(TicketStatus.OPEN)
                    .stay()
                    .to(TicketStatus.IN_PROGRESS).move()
                    .forbidOthers("start before completing")
                .from(TicketStatus.IN_PROGRESS)
                    .stay()
                    .to(TicketStatus.DONE).action("complete", ctx -> ctx.entity().isApproved()
                            ? ActionOutcome.ok() : ActionOutcome.failed("approval declined"))
                        .onFailure(TicketStatus.FAILED)
                    .to(TicketStatus.FAILED).move()
                    .forbidOthers("complete or fail")
                .from(TicketStatus.DONE)
                    .stay()
                    .forbidOthers("done is final")
                .from(TicketStatus.FAILED)
                    .stay()
                    .forbidOthers("failed is final")
                .build();

        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph)
                        .state(Ticket::getStatus, Ticket::setStatus)
                        .identity(Ticket::getId))
                .build()) {
            registry.of(key).transitionTo(ticket, TicketStatus.IN_PROGRESS);
            return registry.of(key).transitionTo(ticket, TicketStatus.DONE);
        }
    }
}
```

Unlike the typed annotation vocabulary, the DSL declares diagonal `.stay()` cells explicitly. It also exposes
runtime functions directly, so this alternative does not need generated action symbols. It deliberately omits
the quickstart's title guard and completion hook; add them through the DSL when that behavior is required.

Other DSL stages support named guards, async guards, async actions, `failsOn`, `afterCommit`, watches and cycle
allowances. A low-level `StateMachine` exposes `transitionTo` and `async().transitionTo`; a generated application
facade intentionally presents the domain operations instead. Keep registry lifetime explicit to close owned
executors.

## Diagnostics and troubleshooting

Read the full compiler message, including the source location and member it identifies. A wrong enum type or
symbol category may be an ordinary javac error rather than an `SG` diagnostic.

### Graph and declaration diagnostics

| Code | Meaning / corrective action |
| --- | --- |
| `SG101` | Incomplete matrix in the DSL: declare every cell or close the row. |
| `SG102` | Duplicate cell or row declaration: remove overlapping explicit declarations. |
| `SG103` | Missing/blank reason: provide a useful forbid or row explanation. |
| `SG104` | Invalid diagonal: use STAY, not an action or move to the same state. |
| `SG105` | Duplicate guard declaration: list each guard once per cell. |
| `SG106` | DSL callback has no inferable name: provide the explicit name overload for a lambda. |
| `SG107` | Blank DSL callback or condition name: supply a nonblank label. |
| `SG201` | Missing or repeated DSL initial declaration. |
| `SG202` | Terminal/exits mismatch: terminal states cannot exit, and exitless states must be terminal. |
| `SG203` | Unreachable state: add a real path from initial or remove the state. |
| `SG204` | Unapproved cycle: mark an intentional cycle with `allowsCycle`. |
| `SG205` | Unused cycle allowance: the named state belongs to no cycle. |
| `SG301` | Invalid failure route: declare the original-source-to-failure cell as action or move. |
| `SG302` | Forbidden `failsOn` type: use a specific permitted foreign exception. |
| `SG401` | Watch target has no action/move cell from its watched state. |
| `SG402` | A terminal state declares a watch. |
| `SG403` | More than one watch on the same state. |
| `SG404` | A watch has no rules. |
| `SG502` | Invalid machine or set declaration, such as an unsupported nested interface or incorrect base type. |
| `SG503` | Missing or incompatible public property accessor. |
| `SG504` | Invalid operation annotation/signature; use one typed action/move and `Outcome<E, S> operation(E)`. |
| `SG505` | Exported method does not uniquely fit the action, guard, hook or condition role. |
| `SG506` | A nonterminal state has no closing `Row`. |
| `SG507` | Invalid transition elements: empty `from`, action-only settings without an action, or timeout on a synchronous action. |
| `SG508` | Watch rule must choose exactly one supported predicate alternative. |
| `SG509` | Invalid peer-machine type. |
| `SG510` | Invalid set accessor, duplicate/missing machine or missing watched peer in the set. |
| `SG511` | Invalid timeout duration. |

### Generation and typed binding diagnostics

| Code | Meaning / corrective action |
| --- | --- |
| `SG601` | Bootstrap declaration cannot be resolved, is ambiguous or contradicts another declaration. Check source and dependency types. |
| `SG602` | Manifest/index is missing, incompatible or stale. Run the full generation/compilation lifecycle with aligned versions. |
| `SG603` | Generated output or export collision. Remove the naming conflict; do not overwrite an unowned file. |
| `SG604` | Invalid or contradictory export/role. Check markers and exported signatures. |
| `SG605` | Symbol has no valid original member, or belongs to the wrong host/role. Use the correct generated reference. |
| `SG606` | Graph or watch vocabulary belongs to another machine/pair. |
| `SG607` | Too many values in an optional singleton element. |
| `SG608` | Invalid, duplicated or unused peer, or a watched peer outside this compilation. |
| `SG609` | Legacy string-based graph declaration. Migrate to generated typed vocabulary. |

### Runtime exceptions

| Exception | What to investigate |
| --- | --- |
| `UninitializedStateException` | Entity state was null. Initialize or correctly hydrate it before calling the machine. |
| `TransitionAbortedException` | A technical failure aborted execution; inspect cause, path and action journal. |
| `UnhandledFailureException` | An action failed without an `onFailure` route. |
| `CascadeException` | Collected nested transition errors; inspect `errors()` and the journal. |
| `CascadeCycleException` | Conflicting transition to an in-flight entity. |
| `CascadeDepthExceededException` | Nested work exceeded the configured cascade depth. |
| `WatchChainExceededException` | A watch chain exceeded its limit; inspect reaction cycles. |
| `AmbiguousIdentityException` | Different instances represented one machine identity in the same unit of work. |
| `ReentrantTransitionException` | An action/guard entered another root operation; use context peer/cascade APIs. |
| `PoisonedUnitOfWorkException` | Work continued after an earlier nested abort. |
| `StaleContextException` | A context or context-derived view was used after invalidation. |
| `java.lang.WrongThreadException` | Context peer/cascade access or a derived peer view was used from another thread; it can appear as the cause of an abort. |
| `UnknownFacadeException` / `UnknownPeerMachineException` | The requested peer was not registered in the set/registry. |
| `CommitFailedException` | Pre-commit state check failed or a setter threw; inspect applied/pending entities before retrying. |
| `AfterCommitException` | A hook or listener threw; inspect whether state already committed. |
| `InvalidGraphException` | Programmatic DSL definition failed validation; inspect its problem list. |

These are not HTTP status codes. Map rejected transitions, business failures and technical failures to your
application's own API contract.

### Common build problems

| Symptom | Remedy |
| --- | --- |
| `TicketsGraph` / `TicketRefs` cannot be found | Run generation before javac; check processor participation and original source roots. |
| Gradle plugin cannot resolve | Add `mavenCentral()` to `pluginManagement.repositories`. For local development builds, publish the implementation **and marker** locally and add `mavenLocal()` there too. |
| Core resolves but Maven plugin does not | Configure `<pluginRepositories>` as well as `<repositories>` for an internal repository. |
| Processor reports no manifest | Use the Gradle plugin or add Maven's per-execution manifest arguments and six goals. |
| A Lombok accessor is missing | Put Lombok first on the processor path, or provide a public handwritten accessor. |
| A newly renamed symbol still appears | Run the normal lifecycle, check whether generation succeeded, and inspect the original declaration and ownership diagnostics. |
| Generation task skips | That source set does not declare `graphmaquina-processor` on its annotation-processor configuration. |
| Async graph cannot be built | Supply a positive default timeout through the generated set builder. |
| Watch never triggers | Check relationship accessors, owner state, first-match rules, target guards and whether a peer transition actually committed. |
| Strict lint reports unclaimed third-party annotations | Inspect the application's processor setup. GraphMaquina claims its own declarations; it is not a wildcard processor for Spring, Jakarta or test annotations. |

For a reproducible issue, include JDK/build-tool versions, relevant build configuration, the minimal graph and
binding declarations, the full diagnostic and whether the problem occurs on a clean build. Report issues at
[GitHub Issues](https://github.com/graphmaquina/graphmaquina/issues).

## Migration from StateGraph

For existing consumers, update both build coordinates and source imports:

| Old naming / declaration | Current form |
| --- | --- |
| `stategraph-*` modules | `graphmaquina-*` artifacts under `io.github.graphmaquina` |
| Old package prefix | `io.github.graphmaquina` |
| String-based `@StateGraph` | `@GenerateGraphApi` plus `@<Machine>Graph.Definition` |
| `action = "charge"`, `by = Host.class` | `action = HostRefs.Charge.class` with `@GraphAction` on the method |
| String guard or hook names | Marked, generated `Refs` class literals |
| String state names | Actual enum constants |
| Implicit state/id properties | Explicit `state` and `id` property symbols |
| String watch property names | Property symbols and generated owner/peer vocabulary |
| ISO-8601 annotation timeout string | `@Timeout(seconds = ..., nanos = ...)` |

Legacy annotation classes remain in the source tree for diagnostics, but declarations using them are rejected
with `SG609`; they are not a compatibility mode. Do not rename retained Java API names such as `Machines`,
`Machine`, `@StateGraphs` or `io.github.graphmaquina.dsl.StateGraph` by guesswork.

The [detailed migration guide](docs/typed-annotations-migration.md) preserves the historical v0.2 → typed-v0.3
transition, with before/after declarations. This README describes the current `1.0.0` coordinates and workflow.

## Known limitations

| Area | Current status |
| --- | --- |
| Watches between JARs/modules | Unsupported: both machines must be generated in one compilation. External peers fail with `SG608`; [issue #1](https://github.com/graphmaquina/graphmaquina/issues/1). |
| IDE acceptance | Generated-root build integration is automated; manual IntelliJ navigation, editor completion and rename/regeneration checks remain pending. |
| External payments demo | Its strict-lint acceptance is still partial; it is outside this repository's library test totals. |
| Durability and concurrent access | Application responsibilities; there is no automatic DB transaction, durable watch queue or entity lock. |
| Async timeout | Stops waiting; does not terminate synchronous code or cancel a user's running stage. |
| Public compatibility matrix | Java 21 is the baseline; no claim of verification across all newer JDKs or Gradle versions. |

See [the audit verification record](docs/audit-fixes-verification.md) for tested guarantees and remaining
acceptance gaps. Historical design documents are not evidence that a deferred feature is implemented.

## Build, verify and release this repository

These commands run from the **GraphMaquina checkout**, not the ticket application's directory:

```bash
./gradlew --no-daemon build
./gradlew --no-daemon test :graphmaquina-gradle-plugin:test
./gradlew :graphmaquina-example:test
./gradlew :graphmaquina-codegen:test :graphmaquina-processor:test
./gradlew :graphmaquina-maven-plugin:test
```

The second command explicitly includes the Gradle adapter's TestKit suite. Its plugin is a separate included
build, while the Maven adapter's integration suite drives actual Maven builds. Have `mvn` available to run it.
The example applies the included plugin and uses the sibling generator project.

The [2026-09-09 audit](docs/audit-fixes-verification.md) recorded **444 tests with no failures, errors or skipped
tests** across the six suites. That is a dated verification result, not a promise that every future checkout
has the same count. Test reports are under each module's `build/reports/tests/test`.

### Prepare a Maven Central bundle

Release preparation is a maintainer operation. Version `1.0.0` is already published and cannot be replaced
or deleted from Maven Central. For a new release, update the version consistently in the convention plugin
and the separate Gradle plugin build before creating a bundle. Configure `SIGNING_KEY` with the project's
ASCII-armored private release key and `SIGNING_PASSWORD` with its passphrase through your release
environment. Then run:

```bash
./gradlew --no-daemon centralBundle
```

Maintainers using 1Password CLI can inject both secrets for this command without copying them into the
checkout or shell history. Replace `<vault>` and `<item>` with the vault and signing item's names or IDs;
the item must have a `private_key` field containing the armored secret key and a `password` field:

```bash
SIGNING_KEY='op://<vault>/<item>/private_key' \
SIGNING_PASSWORD='op://<vault>/<item>/password' \
op run -- ./gradlew --no-daemon --no-configuration-cache centralBundle
```

The `op://` values are references, not credentials. `op run` resolves them into the child process's
environment and masks secrets in its output. Keep the private key, passphrase and revocation certificate
in the vault. Only distribute the public key. See [release signing](docs/release-signing.md) for the
public fingerprint, key retrieval and verification commands.

The task stages five binary publications and the Gradle plugin marker, validates required artifacts and POM
metadata, checks signature files and checksums, then produces:

```text
build/graphmaquina-central-bundle.zip
```

Central staging requires the signing key; development installation to Maven local does not. The task uploads
nothing automatically. Upload the prepared bundle through Central Portal with the authorized namespace and
release credentials. Portal validation and public availability remain separate steps. The local structural
validator does not replace cryptographic signature verification or Portal checks.

Do not publish a bundle signed with a temporary test key. See the
[release preparation reference](docs/typed-annotations-build.md#preparing-a-central-release) for details.

## Further reference

| Reference | What to use it for |
| --- | --- |
| [Build integration guide](docs/typed-annotations-build.md) | Adapter lifecycle, toolchain checks, paths and repository configuration |
| [Typed annotation migration](docs/typed-annotations-migration.md) | Historical string-to-symbol migration and naming rules |
| [Shop source](graphmaquina-example/src/main/java/io/github/graphmaquina/example) | Complete two-machine domain with async payment, guards, cascades and watches |
| [Shop tests](graphmaquina-example/src/test/java/io/github/graphmaquina/example) | Executable behavior and expected diagrams |
| [Public API source](graphmaquina-core/src/main/java/io/github/graphmaquina/api) | Exact signatures and outcome types |
| [Programmatic DSL source](graphmaquina-core/src/main/java/io/github/graphmaquina/dsl) | Lower-level builder stages |
| [Audit of 64 decisions](docs/audit-64-decisions.md) | Decision-by-decision implementation evidence and limitations |
| [Audit fixes and verification](docs/audit-fixes-verification.md) | Regression fixes, test totals and release-bundle checks |
| [Historical acceptance record](docs/typed-annotations-verification.md) | Earlier acceptance runs, clearly distinguished from current audit results |

## License

GraphMaquina is licensed under Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The code emitted into a consuming project by this project's generator and annotation processor belongs to
that project. The repository's license notice states that its license does not extend to that generated output.
