# StateGraph

State machines declared as a graph: an exhaustive N×N matrix, cross-machine cascades, declared watches and
asynchronous actions — all checked by javac.

A machine is one annotated interface. Every cell of its matrix is either declared or closed with a reason, so
"can this entity go from here to there?" always has an answer written in the source. A code generator turns the
declarations into a typed vocabulary, an annotation processor turns the interface into a facade, and the
runtime executes the transitions.

**Version: `1.0.0`, under `io.github.graphmaquina`. Publication to Maven Central is pending.** The declarations are typed: no state,
method or property is written as a string. If you are coming from 0.2, read
[docs/typed-annotations-migration.md](docs/typed-annotations-migration.md).

## Modules

| Module | Artifact | Role |
| --- | --- | --- |
| `graphmaquina-core` | `io.github.graphmaquina:graphmaquina-core` | the runtime, the markers and the symbol categories — the only artifact the application depends on |
| `graphmaquina-codegen` | `io.github.graphmaquina:graphmaquina-codegen` | the generator that writes the typed vocabulary before javac runs |
| `graphmaquina-processor` | `io.github.graphmaquina:graphmaquina-processor` | the annotation processor that validates the graph, emits the facades and publishes the export index |
| `graphmaquina-gradle-plugin` | plugin id `io.github.graphmaquina` | the Gradle build integration (an included build of this repository) |
| `graphmaquina-maven-plugin` | `io.github.graphmaquina:graphmaquina-maven-plugin` | the Maven build integration, six goals |
| `graphmaquina-example` | — | a two-machine shop used as the reference consumer |

## What a machine looks like

The entity marks the properties the graph reads. Lombok may write the accessors; the marker goes on the field
when a conventional accessor exists, and on the accessor otherwise.

```java
public final class Item {

    @GraphProperty @Getter private final String id;
    @GraphProperty @Getter private ItemStatus status = ItemStatus.PENDING;
    @GraphProperty @Getter private final Optional<Order> order;
    …
}
```

The code behind a transition marks what it exports:

```java
public final class ItemActions {

    @GraphAction
    public ActionOutcome pack(TransitionContext<Item, ItemStatus> ctx) {
        return ActionOutcome.ok();
    }
}
```

The machine declares the graph with the vocabulary generated for it — `ItemsGraph` for the machine `Items`,
`ItemRefs` and `ItemActionsRefs` for the exported members:

```java
@GenerateGraphApi
@ItemsGraph.Definition(initial = PENDING, terminals = {PACKED, CANCELLED},
        state = ItemRefs.Status.class, id = ItemRefs.Id.class)
@ItemsGraph.Row(state = PENDING, otherwise = "an item is packed by going through PACKING")
@ItemsGraph.Row(state = PACKING, otherwise = "cannot go back to pending")
@ItemsGraph.Cell(from = {PENDING, PACKING}, to = CANCELLED)
public interface Items extends Machine<Item, ItemStatus> {

    @ItemsGraph.Move(from = PENDING, to = PACKING)
    Outcome<Item, ItemStatus> startPacking(Item item);

    @ItemsGraph.Action(from = PACKING, to = PACKED, action = ItemActionsRefs.Pack.class)
    Outcome<Item, ItemStatus> pack(Item item);
}
```

Everything in those declarations is a typed reference: `PENDING` is the enum constant, `ItemRefs.Status` is the
symbol of the entity's `status` property, `ItemActionsRefs.Pack` is the symbol of the marked method. A state of
another machine, a guard where an action belongs or a method that was never marked does not compile.

A set of machines is built together, and the processor generates its builder:

```java
@StateGraphs
public interface ShopMachines extends Machines {
    Orders orders();
    Items items();
}

ShopMachines shop = ShopMachines_.builder(Duration.ofSeconds(10), new OrderActions(…), new ItemActions())
        .build();
Outcome<Item, ItemStatus> outcome = shop.items().pack(item);
```

Watches connect two machines: `@GraphPeers(Items.class)` on the owner, and
`@OrdersItems.Watch(state = PAID, peers = OrderRefs.Items.class, owner = ItemRefs.Order.class, rules = …)`
re-evaluates the order when its items move.

## Building against it

**Generation is mandatory and runs before compilation.** The typed vocabulary does not exist until the
generator has written it, so a build must run the generator, then javac with the annotation processor, then the
completion step that publishes the index of what javac accepted. Both build adapters do exactly that.

Install the artifacts locally before using the coordinates below; see the
[local installation commands](docs/typed-annotations-build.md#where-the-artifacts-come-from). Configure
`mavenLocal()` in both dependency repositories and `pluginManagement.repositories`.
After publication is confirmed, use `mavenCentral()` in both places: the Gradle plugin is intended for
Central, including its plugin marker. See [preparing a release](docs/typed-annotations-build.md#preparing-a-central-release)
for the signed bundle workflow.

Gradle:

```kotlin
plugins {
    java
    id("io.github.graphmaquina") version "1.0.0"
}

dependencies {
    implementation("io.github.graphmaquina:graphmaquina-core:1.0.0")
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")           // Lombok first
    annotationProcessor("io.github.graphmaquina:graphmaquina-processor:1.0.0")
}
```

Maven needs `graphmaquina-maven-plugin` with its six goals plus the manifest argument on each compiler
execution. Both setups, the requirements they enforce and their troubleshooting are in
[docs/typed-annotations-build.md](docs/typed-annotations-build.md). A Java 21 toolchain or later is required.

## Documentation

| Document | Content |
| --- | --- |
| [docs/typed-annotations-build.md](docs/typed-annotations-build.md) | Gradle and Maven setup, the six goals, the lifecycle, what the adapters require, troubleshooting |
| [docs/typed-annotations-migration.md](docs/typed-annotations-migration.md) | v0.2 → v0.3 step by step, with the example's `Orders` before and after, and the SG601–SG609 diagnostics |
| [docs/typed-annotations-verification.md](docs/typed-annotations-verification.md) | the acceptance criteria of the design and the observed evidence for each |

## Building this repository

```bash
./gradlew build                                   # every module, tests included
./gradlew -p graphmaquina-gradle-plugin test        # the Gradle adapter's TestKit suite
./gradlew :graphmaquina-maven-plugin:test           # the Maven adapter's integration suite (drives real mvn builds)
```

The Gradle adapter is an included build (`pluginManagement { includeBuild("graphmaquina-gradle-plugin") }`), so
`graphmaquina-example` applies `id("io.github.graphmaquina")` without a version and points the `graphmaquinaGenerator`
configuration at the sibling `graphmaquina-codegen` project. Java 21 is required.

## License

Apache License 2.0. The full terms are in [LICENSE](LICENSE), and the attribution notice is in
[NOTICE](NOTICE).

The code this project's generator and annotation processor emit into a consuming project belongs to that
project. It is your output, not a derivative of this library, and this License does not reach it.
