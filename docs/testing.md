# Testing GraphMaquina machines

`graphmaquina-test` is a test-scoped module of typed assertions, probes and graph snapshots for machines
built with GraphMaquina. It asserts over the runtime's own values — definitions, outcomes, transition
records, reactions, engine exceptions — and reports every unmet expectation as a `GraphAssertionError`
carrying the context, the property, the expected value and the actual one.

It brings no test framework of its own. Nothing in its public API mentions JUnit or AssertJ, so it works
under whatever runner your project already uses; the examples here are JUnit 5 because the repository's own
example module is.

- [Install](#install)
- [Your first test](#your-first-test)
- [What each entry point checks](#what-each-entry-point-checks)
- [Verified samples](#verified-samples)
- [Goldens and structural snapshots](#goldens-and-structural-snapshots)
- [What a check does not prove](#what-a-check-does-not-prove)
- [Sharp edges](#sharp-edges)
- [Maintaining test support](#maintaining-test-support)

Every Java sample below is copied verbatim out of a test that runs in this repository. Each section names
the file it came from.

## Install

`graphmaquina-test` is a module of the **next** release. The release the README badge reports does not
contain it, so the coordinates below resolve from Maven Central only once that release is published. Until
then, build it from a checkout of this repository and consume it locally.

The coordinates follow the same version convention as the rest of the guide: a `graphmaquinaVersion` Gradle
property or a `graphmaquina.version` Maven property, set once per build, never a literal repeated per
dependency.

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

The module is test-scoped: it depends on `graphmaquina-core` and adds nothing to your application's runtime
classpath. Keep its version equal to the runtime, generator and processor versions you already align.

Before that release, publish it from a GraphMaquina checkout and resolve it from the local repository. Its
POM pins `graphmaquina-core` at its own version, so publish core under the same version in the same command;
a version that Maven Central does not carry yet has nowhere else to resolve core from:

```bash
./gradlew :graphmaquina-core:publishToMavenLocal :graphmaquina-test:publishToMavenLocal \
    -PgraphmaquinaVersion=<the version you want to resolve>
```

Then add `mavenLocal()` to the repositories your build uses for test dependencies, ahead of Maven Central.
Inside this repository no publication is needed at all: `graphmaquina-example` depends on the project
directly with `testImplementation(project(":graphmaquina-test"))`.

## Your first test

The machines under test are built exactly as the application builds them: a real registry, real entities,
and test doubles only where the domain reaches outside the process. A `TransitionRecorder` is a
`TransitionListener`, so it is installed the way any listener is. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
final FakeGateway gateway = new FakeGateway();
final FakeCarrier carrier = new FakeCarrier();
final TransitionRecorder recorder = TransitionRecorder.create();
final ShopMachines shop = ShopMachinesRuntime.builder(Duration.ofSeconds(10), new OrderActions(gateway, carrier), new ItemActions())
        .listener(recorder)
        .build();
final Orders orders = shop.orders();
final Items items = shop.items();
```

One static import, `io.github.graphmaquina.test.GraphAssertions.assertThat`, reaches the views for every
subject a test holds directly: a definition, an outcome, an action or guard result, a throwable, an entity.
A bare list of transition records and a bare list of reactions would need two overloads with the same
erasure, so they keep their own factories, `TransitionRecordsAssert.assertThat` and
`ReactionsAssert.assertThat`; inside a chain they are reached as `records()` and `reactions()`. The overload
is chosen from the subject's static type, and the chain keeps the entity and state types of that subject, so
the state enum of the machine under test is the only one the compiler accepts along it:

```java
@Test
void operationsThatDoNotApplyAreRejectedWithTheRowReason() {
    Order order = new Order("o1");

    assertThat(orders.ship(order)).isRejected()
            .hasReason("cannot ship without payment")
            .isForbidden();
}
```

## What each entry point checks

| Subject | Entry point | What the view can expect |
| --- | --- | --- |
| Graph definition | `assertThat(definition)` | initial state, terminal set, presence or absence of async, allowed targets of a row, absence of a watch |
| One matrix cell | `assertThat(definition).edge(from, to)` | kind, forbid reason, action name and sync/async mode, guard names and modes in order, failure target, `failsOn` classes, timeout, after-commit hook |
| A watched state | `assertThat(definition).watch(state)` | the peer machine and the ordered watch rules |
| Reachability | `assertThat(definition).topology()` | nodes, successors, reachable set, an explicit walk, the shortest path, absence of a path, cycles |
| Outcome | `assertThat(outcome)` | the variant, then `from`, requested target, recorded state, live entity state, entity identity |
| Applied outcome | `assertThat(outcome).isApplied()` | `movedTo`, plus `records()` and `reactions()` |
| Failed outcome | `assertThat(outcome).isFailed()` | `movedTo`, reason, cause, plus `records()` and `reactions()` |
| Rejected outcome | `assertThat(outcome).isRejected()` | reason, whether the cell is forbidden, the rejecting guard's name |
| Transition records | `.records()`, or `TransitionRecordsAssert.assertThat(list)` | size, exact content, and per entity: kind, from, to, action, depth, convergence |
| Reactions | `.reactions()`, or `ReactionsAssert.assertThat(list)` | size, absence of aborts directly or recursively, and per entity: `isDone()` with its nested outcome, or `isAborted()` with its cause |
| Engine failure | `assertThat(captureFailure(() -> ...))` | abort, cascade abort, commit failure, after-commit failure, invalid graph; cause identity or type; cascade path; journal |
| Action result, guard result | `assertThat(actionOutcome)`, `assertThat(guardResult)` | ok or failed, pass or rejected, and the reason |
| Live entity | `assertThat(entity, Order::status)` | the state the getter reads at that moment |
| Listener callbacks | `TransitionRecorder.assertRecorded()` | size, the sequence of machine keys, the sequence of outcome kinds, and the outcome sequence of one entity |
| Tracked entities | `StateSnapshot` | which of the tracked entities moved since the capture, and that none did |
| Completion stage | `AsyncAssertions.await`, `AsyncAssertions.awaitFailure` | the outcome, or the throwable, the stage ended with inside the test's budget |
| Guard, action, hook | `GuardProbe`, `ActionProbe`, `HookProbe`, `CallTrace` | scripted answers per call, the calls each probe recorded, and the order callbacks were entered in |
| State setter | `StateWriteProbe` | every write in entry order with its status, and a failure armed on one call |
| Async callback | `ControlledAsyncAction`, `ControlledAsyncGuard` | that the engine entered the callback, with the test deciding when and how it answers |
| Rendered diagram | `GraphGoldens.assertMatches` | visual DOT output only; not machine logic |
| Whole definition | `GraphSnapshots.assertMatches` | every field the definition carries against a checked-in resource |

## Verified samples

### The graph, its watch and its cells

Names are the runtime's public names — the name the DSL or the generated companion declared — not the name
of the Java method that implements them. From
[TestingApiTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/TestingApiTest.java):

```java
assertThat(orders.definition())
        .hasInitial(OrderStatus.NEW)
        .hasTerminalsExactly(OrderStatus.SHIPPED, OrderStatus.CANCELLED)
        .hasAsync()
        .hasAllowedTargetsExactly(OrderStatus.NEW,
                OrderStatus.PAID, OrderStatus.PAYMENT_DECLINED, OrderStatus.CANCELLED)
        .watch(OrderStatus.PAID)
        .hasPeer(ItemsRuntime.KEY)
        .hasRulesExactly(List.of(new WatchRuleExpectation<>("allIn PACKED", OrderStatus.SHIPPED)));
```

One cell carries everything the row declared for it, including the parts no diagram shows:

```java
assertThat(orders.definition())
        .edge(OrderStatus.NEW, OrderStatus.PAID)
        .isAction()
        .hasActionNamed("pay")
        .hasAsyncAction()
        .hasGuardsExactly(new GuardExpectation("retryBudgetLeft", false))
        .hasTimeout(Duration.ofSeconds(5))
        .hasFailureTarget(OrderStatus.PAYMENT_DECLINED)
        .failsOnExactly(IOException.class)
        .hasNoHook();
```

A forbidden cell is asserted the same way, and its reason is the row's own text:

```java
assertThat(items.definition())
        .edge(ItemStatus.PENDING, ItemStatus.PACKED)
        .isForbidden()
        .hasReason("an item is packed by going through PACKING")
        .hasNoAction();
```

`hasGuardsExactly` keeps order and multiplicity, because the engine evaluates guards in declaration order
and stops at the first rejection. `failsOnExactly()` with no argument asserts that the cell maps no
exception to its failure route.

### Outcomes, reactions and observed state

`movedTo` reads the state the outcome recorded; `hasEntityState` reads the entity through a getter at the
moment the expectation runs. A reaction is reached from the outcome that carries it, selected by machine and
by entity identity. From [OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
assertThat(orders.pay(order)).isApplied().movedTo(OrderStatus.PAID)
        .hasEntityState(Order::status, OrderStatus.PAID);
```

```java
assertThat(items.pack(i2)).isApplied().reactions()
        .forEntity(OrdersRuntime.KEY, order).single().isDone().outcome()
        .isApplied().movedTo(OrderStatus.SHIPPED)
        .hasEntityState(Order::status, OrderStatus.SHIPPED);
```

A guard rejection names the guard; a domain failure keeps the very exception instance the action reported:

```java
assertThat(orders.retry(order)).isRejected().isRejectedByGuardNamed("retryBudgetLeft");
```

```java
assertThat(orders.pay(order)).isFailed().hasCauseSameAs(down)
        .hasEntityState(Order::status, OrderStatus.PAYMENT_DECLINED);
```

### Failures and the journal

An aborted transition throws rather than returning an outcome, so the subject is the throwable.
`captureFailure` runs the operation and hands back what it threw; it catches no `Error`, so an
`AssertionError` raised inside the body still fails the test the way it should. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
assertThat(captureFailure(() -> orders.cancel(order))).isCascadeAbort()
        .hasErrorCount(1)
        .journal().hasActionsExactly("cancel").hasStatusesExactly(ActionStatus.THREW);
assertThat(order, Order::status).isIn(OrderStatus.NEW);
assertThat(packed, Item::status).isIn(ItemStatus.PACKED);
assertThat(pending, Item::status).isIn(ItemStatus.PENDING);
```

### The listener recorder

The recorder keeps the callbacks the engine published, in the order it published them, and nothing else. It
never walks `reactions()`, so a reaction is counted once — as its own callback — even though the root
outcome carries it too. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
recorder.assertRecorded()
        .hasSize(9)
        .hasKeysExactly(OrdersRuntime.KEY, OrdersRuntime.KEY, ItemsRuntime.KEY, OrdersRuntime.KEY,
                ItemsRuntime.KEY, OrdersRuntime.KEY, ItemsRuntime.KEY, OrdersRuntime.KEY,
                ItemsRuntime.KEY)
        .hasOutcomeKindsExactly(Outcome.Applied.class, Outcome.Ignored.class, Outcome.Applied.class,
                Outcome.Ignored.class, Outcome.Applied.class, Outcome.Ignored.class,
                Outcome.Applied.class, Outcome.Applied.class, Outcome.Applied.class);
```

A registry accepts one listener. When the test needs its own as well, `TransitionRecorder.compose(...)`
shares the slot. An entity keeps moving after its callback, so if the assertion must see the entity as it
was, record a projection with `TransitionRecorder.projecting(...)` instead of the live outcome.

### Tracked entity state

A `StateSnapshot` reports only the entities it was given, so an entity the test does not name may move
without ever failing `assertUnchanged()`. From
[TestingApiTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/TestingApiTest.java):

```java
private StateSnapshot track(Order order, Item first, Item second) {
    return StateSnapshot.builder()
            .track(OrdersRuntime.KEY, order, Order::status)
            .track(ItemsRuntime.KEY, first, Item::status)
            .track(ItemsRuntime.KEY, second, Item::status)
            .capture();
}
```

```java
StateSnapshot before = track(order, packed, pending);

assertThat(captureFailure(() -> orders.cancel(order))).isCascadeAbort().hasErrorCount(1);

before.assertUnchanged();
```

`assertUnchanged()` fails with every entity that moved, not only the first, so one run names the whole
difference. `changes()` reads every tracked getter again on each call and answers about the entities as they
are now.

### Probes

A probe stands where a guard, an action or a hook stands and answers from an explicit script. A shared
`CallTrace` records the order the callbacks were entered in, which is how the engine's short-circuit becomes
observable. From [ProbeTest](../graphmaquina-test/src/test/java/consumer/ProbeTest.java):

```java
CallTrace trace = CallTrace.create();
GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
        .thenReturn(GuardResult.reject("no title"));
GuardProbe<Entity, State> second = GuardProbe.<Entity, State>create("second", trace)
        .otherwiseReturn(GuardResult.pass());
ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
        .otherwiseReturn(ActionOutcome.ok());
```

```java
trace.assertNamesExactly("first");
second.calls().assertCount(0);
action.calls().assertCount(0);
```

`thenReturn` and `thenThrow` queue one answer per call, in order; `otherwiseReturn` and `otherwiseSucceed`
set the standing answer used once the queue is empty. A probe that runs out of answers says which probe and
which call ran out, rather than letting the machine misbehave.

`StateWriteProbe` wraps the machine's state setter, which is how a test watches phase 2 and makes one write
fail. Calls are numbered from 1, and an armed call throws before the wrapped setter is reached. From
[StateWriteProbeTest](../graphmaquina-test/src/test/java/consumer/StateWriteProbeTest.java):

```java
Entity entity = new Entity("e", State.NEW);
IllegalStateException boom = new IllegalStateException("setter down");
StateWriteProbe<Entity, State> writes =
        StateWriteProbe.<Entity, State>wrapping(Entity::setState).failOnCall(2, boom);
writes.accept(entity, State.READY);
assertSame(boom,
        assertThrows(IllegalStateException.class, () -> writes.accept(entity, State.DONE)));
assertEquals(State.READY, entity.state());
assertEquals(List.of(WriteStatus.RETURNED, WriteStatus.THREW),
        writes.writes().stream().map(StateWrite::status).toList());
```

### Operation input in recorded calls

`GuardCall` and `ActionCall` capture `Optional<?> input` when the callback is entered. This works with
`GuardProbe`, `ActionProbe`, `ControlledAsyncGuard` and `ControlledAsyncAction`, including calls in a
`CallTrace` or a `PendingInvocation`. It stores the argument reference without retaining the live context;
reading the history after the transaction ends is safe. The payload is not copied: later mutations of a
mutable argument remain visible, so use an immutable value when the test needs a stable value snapshot.

The [published consumer test](../graphmaquina-test/src/test/java/consumer/PublishedArtifactSmokeTest.java)
invokes an operation with a string argument named `input`, then checks both recorded calls:

```java
assertThat(guard.calls().single()).hasInput("approved").hasInputSameAs(input);
assertThat(action.calls().single()).inputSatisfies(CharSequence.class, value -> {
    if (value != input) { throw new AssertionError("input identity was lost"); }
});
```

`hasInput` compares by equality, `hasInputSameAs` checks identity, and `hasNoInput` requires absence.
`inputSatisfies` checks presence and assignability before invoking the typed assertion; an absent or wrong
input produces an inspectable `GraphAssertionError`. Null expected values are programming errors; use
`hasNoInput()` to check a null operation argument. `CallInputAssert.assertThat(call)` is also available
without the `GraphAssertions` facade.

The recorded `input(Class)` follows the context contract: empty remains empty, supertypes are supported,
and incompatible present values throw `ClassCastException`. A null type token throws `NullPointerException`;
primitive tokens (including `void.class`) throw `IllegalArgumentException`, even for an empty input. Use
wrapper classes for primitive operation arguments.

Root guards, actions and failure actions record the root input. Cascades, watch reactions and reevaluation
have their own calls with empty input; a later invocation never reuses a previous input. For a controlled
async callback, inspect `assertThat(pending.call()).hasInputSameAs(verdict)` before answering it or after
completion. The same reference remains in `fixture.invocations()` after close.

The original three-argument `GuardCall` and four-argument `ActionCall` constructors remain available and
create calls with empty input. The records now have an additional component: record patterns must include
it, and generated equality, hash codes and string representations include it.

Executable examples: [ProbeInputTest](../graphmaquina-test/src/test/java/consumer/ProbeInputTest.java) and
[CallInputAssertionsTest](../graphmaquina-test/src/test/java/consumer/CallInputAssertionsTest.java). The
published-artifact consumer also compiles and runs the new API directly from the staged JARs.

### Controlled asynchronous invocations

A `ControlledAsyncAction` or `ControlledAsyncGuard` is declared on the cell in place of the real async
callback. The test resumes when the engine has entered it, and the engine resumes when the test answers that
very invocation, so an asynchronous transition is asserted without a single sleep. `awaitInvocation` and
`AsyncAssertions.await` each take a budget that belongs to the test. The fixture is `AutoCloseable`; closing
is idempotent, cancels the stages it created itself and rejects any invocation that arrives afterwards.
Concurrent invocations are independent: each arrives with its own handle and is answered on its own. From
[ControlledAsyncTest](../graphmaquina-test/src/test/java/consumer/ControlledAsyncTest.java), where
`GENEROUS` is that test's own two-second budget:

```java
ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(5), action));
try (registry; action) {
    Entity first = new Entity("first", State.NEW);
    Entity second = new Entity("second", State.NEW);
    CompletableFuture<Outcome<Entity, State>> firstResult =
            registry.of(KEY).async().transitionTo(first, State.READY);
    CompletableFuture<Outcome<Entity, State>> secondResult =
            registry.of(KEY).async().transitionTo(second, State.READY);
    PendingInvocation<ActionCall<Entity, State>, ActionOutcome> one =
            action.awaitInvocation(GENEROUS);
    PendingInvocation<ActionCall<Entity, State>, ActionOutcome> other =
            action.awaitInvocation(GENEROUS);
    assertNotSame(one, other);
    assertNotSame(one.call().entity(), other.call().entity());
    assertEquals(2, action.invocations().size());

    PendingInvocation<ActionCall<Entity, State>, ActionOutcome> firstCall =
            one.call().entity() == first ? one : other;
    PendingInvocation<ActionCall<Entity, State>, ActionOutcome> secondCall =
            firstCall == one ? other : one;
    assertTrue(firstCall.complete(ActionOutcome.ok()));
    AsyncAssertions.await(firstResult, GENEROUS).isApplied().hasEntitySameAs(first);
    assertFalse(secondCall.isDone());
    assertFalse(secondResult.isDone());
    assertEquals(State.NEW, second.state());

    assertTrue(secondCall.complete(ActionOutcome.ok()));
    AsyncAssertions.await(secondResult, GENEROUS).isApplied().hasEntitySameAs(second);
}
```

List the registry first and the fixture second inside the try-with-resources header, as that sample does.
The fixture is still constructed first — the graph is built from it, so it exists before the registry does —
and only the order inside the header decides how the two are closed. Try-with-resources closes in reverse
order, so the fixture is closed first, which is what `ControlledAsyncAction` asks for: closing cancels the
invocations it is still holding, and a worker parked inside the action is released before the registry waits
for it. Listed the other way round, a test that fails before answering an invocation makes the registry wait
out the machine's whole `asyncTimeout` — once per unanswered invocation — before the fixture ever gets to
cancel it.

The machine that holds an async cell must declare `asyncTimeout(...)`, as any async machine does. That
deadline is the engine's; the budgets above are the test's, and the two are unrelated — see
[what a check does not prove](#what-a-check-does-not-prove).

## Goldens and structural snapshots

Machine contracts and visual output have separate tests. Do not use a graphical export or
`definition().render()` to validate machine logic.

- `GraphSnapshots.assertMatches(definition, resource)` compares declared metadata with a checked-in
  `.graph` file: the machine key, ordered states, initial state, terminals, async mode, watches and ordered
  rules, and every cell's kind, reason, action and guard names/modes, failure route, `failsOn` classes,
  timeout and after-commit hook. It reads `GraphDefinition` and `EdgeSpec` directly and does not call an
  exporter. Use typed graph assertions for focused expectations. Neither approach executes domain logic;
  test guards, actions, cascades and watches through real operations and assert their outcomes, records,
  reactions and observed entity states.
- `GraphGoldens.assertMatches(definition, resource)` compares the Graphviz DOT output with a checked-in
  `.dot` file. Its default detail is `TRANSITABLE`. This is a visual regression test only: a diagram change
  must not be the evidence for a change in machine behavior.

The `.graph` resources from before the Graphviz migration remain unchanged. Only visual goldens move from
the previous format to `.dot`. `PublishedArtifactSmokeTest` compiles and runs a semantic consumer after
removing every graphic-export class from the core JAR; assertions, state observation and structural
snapshots must still work.

From [DiagramsTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/DiagramsTest.java):

```java
@Test
void orderDiagramMatchesGolden() {
    GraphGoldens.assertMatches(orderGraph(), "golden/order.dot");
}
```

From [GraphContractsTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/GraphContractsTest.java):

```java
@Test
void orderContractMatchesSnapshot() {
    GraphSnapshots.assertMatches(orderGraph(), "contracts/order.graph");
}
```

A mismatch reports the lines that changed, or for a snapshot the field paths that differ — not both files in
full, and both it and a missing golden end with the flags that rewrite the file. In read mode a stored
snapshot of an unknown version or a broken format is reported as the fixture problem it is instead of being
compared, and carries no such flags. An
update run never reads the stored text as a snapshot: it replaces the file with a snapshot of the current
version, so an unreadable one is overwritten rather than reported, and the review of the rewritten file is
what checks the change.

### Reading and updating

The two directions deliberately resolve differently, and neither can overwrite the other's file:

| Mode | Resource resolved through | Written to |
| --- | --- | --- |
| Read (the default) | the class loader, so the copy on the test class path | nothing |
| Update | not read as a golden at all | the source root named by `graphmaquina.goldens.root` |

Two system properties select the mode. `graphmaquina.goldens.update` accepts only the exact values `true`
and `false`; `graphmaquina.goldens.root` names the source root an update writes into and is required when
update mode is on. The resource string is a class-path-relative path such as `golden/order.dot`, and update
mode writes it at that same relative path under the root, so pointing the root at `src/test/resources` puts
the file back where the class path picked it up.

### Gradle

The source root has to be resolved when the build is configured, because a test worker has no `project` to
ask. An update run writes files instead of producing task outputs, so it must be neither skipped as
up to date nor served from the build cache. This is the configuration `graphmaquina-example` applies, in
full:

```kotlin
val updateGoldens = providers.systemProperty("graphmaquina.goldens.update")
    .map { it.toBooleanStrict() }.orElse(false)
tasks.test {
    systemProperty("graphmaquina.goldens.update", updateGoldens.get().toString())
    systemProperty("graphmaquina.goldens.root",
        layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
    outputs.upToDateWhen { !updateGoldens.get() }
    outputs.doNotCacheIf("Golden update writes source resources") { updateGoldens.get() }
}
```

Rewrite the goldens with:

```bash
./gradlew test -Dgraphmaquina.goldens.update=true
```

Then read the diff and commit it. An update run is a deliberate act: it makes the test pass by definition,
so the review of the rewritten file is the only thing that checks the change.

### Maven

Surefire passes system properties to the test JVM through `systemPropertyVariables`. Add them to the
Surefire configuration your project already has, and do not pin a Surefire version here without checking the
one your build resolves:

```xml
<systemPropertyVariables>
  <graphmaquina.goldens.update>${graphmaquina.goldens.update}</graphmaquina.goldens.update>
  <graphmaquina.goldens.root>${project.basedir}/src/test/resources</graphmaquina.goldens.root>
</systemPropertyVariables>
```

Declare the default in `<properties>` so a normal build reads rather than writes:

```xml
<graphmaquina.goldens.update>false</graphmaquina.goldens.update>
```

Rewrite the goldens with `mvn test -Dgraphmaquina.goldens.update=true`.

This is property plumbing only. It replaces nothing in your project's GraphMaquina generation setup: the
generate, prepare-compile and complete-compile lifecycle stays exactly as the
[build integration guide](typed-annotations-build.md) describes it.

A test that needs no properties at all can pass options explicitly —
`GoldenOptions.readOnly(loader)` or `GoldenOptions.updating(loader, sourceRoot, reporter)` read none.

## What a check does not prove

| The check establishes | It does not establish |
| --- | --- |
| `topology().hasPath(...)`: the graph permits this walk | that any run took it. Only an outcome or a transition record shows a hop that happened. |
| `isApplied()`: the root transition committed | that everything it triggered did. A watch reaction inside an applied outcome may have aborted; `reactions().hasNoAbortedRecursively()` is the expectation that rules that out. |
| `movedTo(...)` and `hasRecordedState(...)`: the state the outcome recorded | where the entity is now. `hasEntityState(getter, ...)` reads the live entity, and the entity can move again right after. |
| `calls().assertCount(n)`: a callback was entered n times | that the machine wrote a state. Only `StateWriteProbe.writes()` sees the setter. |
| `journal()`: which actions the aborted attempt entered, and how each ended | that the entities were put back. `StateSnapshot.assertUnchanged()` is the expectation that asserts the rollback. |
| the recorder: every outcome the engine published to the listener | anything about a call that aborted. An abort throws instead of producing an outcome, so no callback is made for it; capture the throwable instead. |
| `AsyncAssertions.await(stage, budget)`: the engine's transition finished | that work the action started in the background ended. Neither wait ever cancels a stage it was handed. |
| that budget: how long the test is willing to wait | the engine's deadline. That is `asyncTimeout(...)` on the machine and `hasTimeout(...)` on the cell, and an expired engine deadline leaves the user's stage alive. |

## Sharp edges

- **`hasNoPath(x, x)` can never hold.** It is the exact negation of `hasShortestPath`, and the graph's
  shortest path from a node to itself is the single-node walk `[x]`. The expectation that answers "can this
  state come back to itself" is `hasReachableExactly`, because `reachableFrom` excludes the origin unless a
  cycle leads back to it.
- **Call ordinals count from 1; list positions count from 0.** `StateWriteProbe.failOnCall(2, ...)` arms the
  second write, and a probe that runs out of scripted answers names the call the same way. The lists are
  plain lists: `writes()`, `calls().snapshot()` and `CallTrace.events()` are indexed from 0, and
  `TraceEvent.sequence()` starts at 0 too. So the write `failOnCall(2, ...)` arms is `writes().get(1)`.
- **`assertCount` returns different things in the two places it appears.** `ProbeCalls.assertCount(int)`
  returns `void`: a probe's calls are the end of a chain. `StateWriteProbe.assertCount(int)` returns the
  probe, so writes keep chaining. This is deliberate in both cases, not an oversight in one.
- **A null subject fails as an assertion; a null expected value fails as a bug in the test.** Handing a view
  a null subject makes the first expectation raise a `GraphAssertionError` whose actual value is `null`,
  never a bare `NullPointerException`. Passing `null` as a required expected value is a programming error
  and keeps raising `NullPointerException` or `IllegalArgumentException`.
- **`ActionStatus.THREW` does not mean your action threw.** The engine also marks actions still in flight as
  `THREW` while it unwinds an abort. The status is the runtime's, and the API does not reinterpret it.
- **A reaction's callback arrives before the root's.** The recorder's sequence reflects that order.
- **`hasAsyncAction()` is about the action alone.** A cell whose guard is async but whose action is not still
  satisfies `hasSyncAction()`.
- **Entities are selected by reference.** `forEntity(key, entity)` matches on identity, so two entities that
  carry the same id are two different subjects.
- **`hasTerminalsExactly` compares as a set; records, guards and reactions keep order and multiplicity.**
- **Assert on the fields, not the type.** A `GraphAssertionError` carries `context()`, `property()`,
  `expected()` and `actual()`. A test that only checks that something was thrown cannot tell a real
  rejection from an incidental failure.

## Maintaining test support

Every GraphMaquina feature or behavior change must provide its counterpart in `graphmaquina-test` in the
same delivery. Extend the relevant assertions, probes, recorded calls or snapshots so a consuming project
can verify the new behavior. Include regression coverage and documented consumer usage. When existing
support already expresses the behavior, demonstrate it with an executable consumer test. Public test APIs
must also be exercised against the staged artifacts by `:graphmaquina-test:publishedArtifactTest`.
