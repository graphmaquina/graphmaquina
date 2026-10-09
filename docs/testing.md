# Testing GraphMaquina machines

`graphmaquina-test` is a test-scoped module of typed assertions, probes and graph snapshots for machines
built with GraphMaquina. It asserts over the runtime's own values — definitions, edges,
node policies, outcomes, transition records, reactions, engine exceptions — and reports every unmet
expectation as a `GraphAssertionError` carrying the context, the property, the expected value and the actual
one.

It brings no test framework of its own. Nothing in its public API mentions JUnit or AssertJ, so it works
under whatever runner your project already uses; the examples here are JUnit 5 because the repository's own
example module is.

- [Install](#install)
- [Your first test](#your-first-test)
- [What each entry point checks](#what-each-entry-point-checks)
- [Asserting on identity](#asserting-on-identity)
- [Verified samples](#verified-samples)
- [Goldens and structural snapshots](#goldens-and-structural-snapshots)
- [What a check does not prove](#what-a-check-does-not-prove)
- [Sharp edges](#sharp-edges)
- [Maintaining test support](#maintaining-test-support)

Every Java sample below runs in this repository. Most are copied verbatim out of a test; the samples of
[failure kinds and recovery rules](#failure-kinds-and-recovery-rules) go the other way, cut out of this page and
run by a test. Each section names the test file.

## Install

Build `graphmaquina-test` from a checkout of this repository and consume it locally, alongside the other
modules of the version you installed.

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

Its POM pins `graphmaquina-core` at its own version, which is why the
[install command](guide.md#configure-gradle) writes both in one invocation:

```bash
./gradlew :graphmaquina-core:publishToMavenLocal :graphmaquina-test:publishToMavenLocal \
    -PgraphmaquinaVersion=<the version you want to resolve>
```

A Gradle build then needs `mavenLocal()` among the repositories it uses for test dependencies, as the
quickstart's [settings.gradle.kts](guide.md#settingsgradlekts) declares; Maven reads that repository
itself and needs no declaration. Inside this repository no publication is needed at all:
`graphmaquina-example` depends on the project directly with
`testImplementation(project(":graphmaquina-test"))`.

## Your first test

The machines under test are built exactly as the application builds them: a real registry, real entities,
and test doubles only where the domain reaches outside the process. A `TransitionRecorder` is a
`MachineObserver`, so it is installed the way any observer is. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
final FakeGateway gateway = new FakeGateway();
final FakeCarrier carrier = new FakeCarrier();
final TransitionRecorder recorder = TransitionRecorder.create();
final ShopMachines shop = ShopMachinesRuntime.builder(Duration.ofSeconds(10), new OrderActions(gateway, carrier), new ItemActions())
        .observer(recorder)
        .build();
final Orders orders = shop.orders();
final Items items = shop.items();
```

One static import, `io.github.graphmaquina.test.GraphAssertions.assertThat`, reaches the views for every
subject a test holds directly: a definition, an outcome, an action or guard result, a throwable, an entity.
A bare list of transition records and a bare list of reactions would need two overloads with the same
erasure, so they keep their own factories, `TransitionRecordsAssert.assertThat` and
`ReactionsAssert.assertThat`; inside a chain they are reached as `records()` and `reactions()`. The overload
is chosen from the subject's static type, and the chain keeps the entity, state and transition types of that
subject, so the two enums of the machine under test are the only ones the compiler accepts along it:

```java
@Test
void anIdentifierWithNoEdgeFromTheCurrentStateIsRejectedByTheNodePolicy() {
    Order order = new Order("o1");

    assertThat(orders.ship(order)).isRejected()
            .hasReason("cannot ship without payment")
            .hasNoTransition()
            .requestedTransition(OrderTransition.SHIP);
    assertThat(order, Order::status).isIn(OrderStatus.NEW);
}
```

The chain carries three type parameters — the entity, the state enum and the transition enum — so a
constant of another machine's catalogue does not compile inside it.

## What each entry point checks

| Subject | Entry point | What the view can expect |
| --- | --- | --- |
| Graph definition | `assertThat(definition)` | initial state, terminal set, presence or absence of async, the identifiers declared from a state, the absence of one identifier, the absence of a watch |
| One edge | `assertThat(definition).transition(from, id)` | destination, kind, action name and sync/async mode, guard names and modes in order, fallback failure identifier, `failsOn` classes, recovery rules in declaration order, timeout, after-commit hook |
| One node's policy | `assertThat(definition).policy(state)` | the default reason and the reason declared for one identifier |
| A watched state | `assertThat(definition).watch(state)` | the peer machine and the ordered watch rules, each naming the identifier it executes |
| Reachability | `assertThat(definition).topology()` | nodes, successors, reachable set, an explicit walk, the shortest path, absence of a path, cycles |
| Outcome | `assertThat(outcome)` | the variant, then `from`, the requested identifier, recorded state, live entity state, entity identity, `hasStatus`, `hasCode`, `hasExplanation` or `hasNoExplanation`, plus `records()` and `reactions()` |
| Applied outcome | `assertThat(outcome).isApplied()` | `movedTo` |
| Failed outcome | `assertThat(outcome).isFailed()` | `hasTarget`, `hasFailureTransition`, `movedTo`, failure kind, reason, cause |
| Rejected outcome | `assertThat(outcome).isRejected()` | reason, `hasNoTransition()` for `RejectionKind.NO_TRANSITION`, and `isRejectedByGuardNamed` for `GUARD` |
| Ignored outcome | `assertThat(outcome).isIgnored()` | `hasReason(IgnoreReason.NO_WATCH)` or `NO_RULE_MATCHED` |
| Transition records | `.records()`, or `TransitionRecordsAssert.assertThat(list)` | size, exact content, and per entity: from, transition, to, kind, action, depth |
| Reactions | `.reactions()`, or `ReactionsAssert.assertThat(list)` | size, absence of aborts directly or recursively, and per entity: `isDone()` with its nested outcome, or `isAborted()` with its cause |
| Engine failure | `assertThat(captureFailure(() -> ...))` | abort, cascade abort, commit failure, after-commit failure, invalid graph; cause identity or type; cascade path; journal; phase, states written and the failing call |
| Action result, guard result | `assertThat(actionOutcome)`, `assertThat(guardResult)` | ok or failed, pass or rejected, the reason, and the failure kind of a failed action |
| Live entity | `assertThat(entity, Order::status)` | the state the getter reads at that moment |
| Completed calls | `TransitionRecorder.assertRecorded()` | size, the sequence of machine keys, the sequence of outcome statuses, and the outcome sequence of one entity |
| Tracked entities | `StateSnapshot` | which of the tracked entities moved since the capture, and that none did |
| Completion stage | `AsyncAssertions.await`, `AsyncAssertions.awaitFailure` | the outcome, or the throwable, the stage ended with inside the test's budget |
| Guard, action, hook | `GuardProbe`, `ActionProbe`, `HookProbe`, `CallTrace` | scripted answers per call, the calls each probe recorded, and the order callbacks were entered in |
| State setter | `StateWriteProbe` | every write in entry order with its status, and a failure armed on one call |
| Async callback | `ControlledAsyncAction`, `ControlledAsyncGuard` | that the engine entered the callback, with the test deciding when and how it answers |
| Rendered diagram | `GraphGoldens.assertMatches` | visual DOT output only; not machine logic |
| Whole definition | `GraphSnapshots.assertMatches` | every field the definition carries against a checked-in resource |

## Asserting on identity

Four expectations answer four different questions about an edge that may or may not be there. They are easy
to confuse, so keep them apart:

| What you want to establish | Expectation |
| --- | --- |
| This origin declares exactly these identifiers | `hasTransitionsExactly(from, ids…)` |
| This identifier has no edge from this origin | `hasNoTransition(from, id)` |
| The request was refused because nothing was keyed | `isRejected().hasNoTransition()` |
| The request was refused by a guard on an edge that does exist | `isRejected().isRejectedByGuardNamed(name)` |

`hasTransitionsExactly` takes identifiers, not destinations, so two edges that reach the same state are two
entries. No set of destinations could express that, which is exactly the point.

An `Ignored` outcome now says which of the two reevaluation cases it is, through
`isIgnored().hasReason(IgnoreReason.NO_WATCH)` or `NO_RULE_MATCHED`. It is the only outcome with no
requested identifier, so `hasNoRequestedTransition()` holds on it and `requestedTransition(id)` holds on
every other variant.

Upgrading tests written against 1.x is a separate document:
[migrating to 2.0](migration-2.0.md) lists every renamed assertion.

## Verified samples

### The graph, its watch and its edges

Names are the runtime's public names — the name the DSL or the generated companion declared — not the name
of the Java method that implements them. From
[TestingApiTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/TestingApiTest.java):

```java
assertThat(orders.definition())
        .hasInitial(OrderStatus.NEW)
        .hasTerminalsExactly(OrderStatus.SHIPPED, OrderStatus.CANCELLED)
        .hasAsync()
        .hasTransitionsExactly(OrderStatus.NEW,
                OrderTransition.PAYMENT_DECLINED, OrderTransition.PAY, OrderTransition.CANCEL)
        .hasTransitionsExactly(OrderStatus.PAYMENT_DECLINED,
                OrderTransition.RETRY, OrderTransition.CANCEL)
        .watch(OrderStatus.PAID)
        .hasPeer(ItemsRuntime.KEY)
        .hasRulesExactly(List.of(new WatchRuleExpectation<>("allIn PACKED", OrderTransition.SHIP)));
```

One edge carries everything its declaration gave it, including the parts no diagram shows:

```java
assertThat(orders.definition())
        .transition(OrderStatus.NEW, OrderTransition.PAY)
        .hasTarget(OrderStatus.PAID)
        .isAction()
        .hasActionNamed("pay")
        .hasAsyncAction()
        .hasGuardsExactly(new GuardExpectation("retryBudgetLeft", false))
        .hasTimeout(Duration.ofSeconds(5))
        .hasFailureTransition(OrderTransition.PAYMENT_DECLINED)
        .failsOnExactly(IOException.class)
        .hasNoHook();
```

An absent edge has no view of its own, because there is nothing to describe. What a test asserts instead is
that nothing is keyed there, and what the node will say when it is asked:

```java
assertThat(items.definition())
        .hasNoTransition(ItemStatus.PENDING, ItemTransition.PACK)
        .policy(ItemStatus.PENDING)
        .hasDefaultReason("an item is packed by going through PACKING");
```

One identifier can leave several states. Each `(from, id)` pair is its own edge, asserted on its own:

```java
assertThat(items.definition())
        .transition(ItemStatus.PENDING, ItemTransition.CANCEL)
        .hasTarget(ItemStatus.CANCELLED)
        .isMove();
assertThat(items.definition())
        .transition(ItemStatus.PACKING, ItemTransition.CANCEL)
        .hasTarget(ItemStatus.CANCELLED)
        .isMove();
```

A terminal is asserted by the absence of everything, not by a kind:

```java
assertThat(orders.definition())
        .hasTransitionsExactly(OrderStatus.SHIPPED)
        .hasNoTransition(OrderStatus.SHIPPED, OrderTransition.CANCEL)
        .policy(OrderStatus.SHIPPED)
        .hasDefaultReason("a shipment cannot be undone");
```

`hasGuardsExactly` keeps order and multiplicity, because the engine evaluates guards in declaration order
and stops at the first rejection. `failsOnExactly()` with no argument asserts that the edge's own `failsOn`
maps no exception to its fallback `onFailure` route; the exceptions a recovery rule maps are not part of it.
`recoversExactly(rules)` compares every rule of the edge, its exception classes included, in declaration order.

### Outcomes, reactions and observed state

`movedTo` reads the state the outcome recorded; `hasEntityState` reads the entity through a getter at the
moment the expectation runs. A reaction is reached from the outcome that carries it, selected by machine and
by entity identity. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

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

A guard rejection names the guard, and asserting the name also asserts `RejectionKind.GUARD`. A domain
failure keeps the very exception instance the action reported, and names the identifier that recovered:

```java
assertThat(orders.retry(order)).isRejected().isRejectedByGuardNamed("retryBudgetLeft");
```

```java
assertThat(orders.pay(order)).isFailed().hasCauseSameAs(down)
        .hasEntityState(Order::status, OrderStatus.PAYMENT_DECLINED);
```

```java
assertThat(orders.pay(order)).isFailed().movedTo(OrderStatus.PAYMENT_DECLINED)
        .hasFailureTransition(OrderTransition.PAYMENT_DECLINED)
        .hasEntityState(Order::status, OrderStatus.PAYMENT_DECLINED);
```

`movedTo` is where the recovering edge landed and `hasFailureTransition` is which edge that was. They are
not the same question: a route may legitimately reach the state another edge also reaches.

### Failures and the journal

An aborted transition throws rather than returning an outcome, so the subject is the throwable.
`captureFailure` runs the operation and hands back what it threw; it catches no `Error`, so an
`AssertionError` raised inside the body still fails the test the way it should. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
assertThat(captureFailure(() -> orders.cancel(order))).isCascadeAbort()
        .hasErrorCount(1).hasPhase(CallPhase.ACTION).hasStatesWritten(StatesWritten.NONE)
        .journal().hasActionsExactly("cancel").hasStatusesExactly(ActionStatus.THREW);
assertThat(order, Order::status).isIn(OrderStatus.NEW);
assertThat(packed, Item::status).isIn(ItemStatus.PACKED);
assertThat(pending, Item::status).isIn(ItemStatus.PENDING);
```

`isCallFailure()` reads any failure of a call through what every kind shares, and the four call-failure views offer
the same expectations: `hasPhase(...)`, `hasStatesWritten(...)`, `hasCallSameAs(...)`, which compares by reference
with the `MachineCall` an observer received, and `journal()`.

### The call recorder

The recorder keeps the outcome of every call that completed, in the order the calls completed, and nothing else. It
never walks `reactions()`, so a reaction is counted once — as its own call — even though the root
outcome carries it too. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
recorder.assertRecorded()
        .hasSize(9)
        .hasKeysExactly(OrdersRuntime.KEY, OrdersRuntime.KEY, ItemsRuntime.KEY, OrdersRuntime.KEY,
                ItemsRuntime.KEY, OrdersRuntime.KEY, ItemsRuntime.KEY, OrdersRuntime.KEY,
                ItemsRuntime.KEY)
        .hasStatusesExactly(APPLIED, IGNORED, APPLIED, IGNORED, APPLIED, IGNORED, APPLIED, APPLIED, APPLIED);
```

A registry accepts any number of observers, so a test adds its own beside the recorder. An entity keeps
moving after its call completes, so if the assertion must see the entity as it was, record a projection with
`TransitionRecorder.projecting(...)` instead of the live outcome.

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
GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
        .thenReturn(GuardResult.reject("no title"));
GuardProbe<Entity, State, Id> second = GuardProbe.<Entity, State, Id>create("second", trace)
        .otherwiseReturn(GuardResult.pass());
ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
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

Root guards, actions and failure actions record the root input, and the recovering action records the input
of the invocation that failed. Cascades, the exits of sub-machines, watch reactions and reevaluation have their
own calls with empty input; a later invocation never reuses a previous input. For a controlled
async callback, inspect `assertThat(pending.call()).hasInputSameAs(verdict)` before answering it or after
completion. The same reference remains in `fixture.invocations()` after close.

Both records carry the identifier that selected the edge:
`GuardCall<E, S, T>(entity, from, transition, to, input)` and
`ActionCall<E, S, T>(entity, from, transition, to, depth, input)`. The shorter constructors — four arguments
for a guard call, five for an action call — remain available and create calls with empty input. Record
patterns must list every component, and generated equality, hash codes and string representations include
them all.

Executable examples: [ProbeInputTest](../graphmaquina-test/src/test/java/consumer/ProbeInputTest.java) and
[CallInputAssertionsTest](../graphmaquina-test/src/test/java/consumer/CallInputAssertionsTest.java). The
published-artifact consumer also compiles and runs the new API directly from the staged JARs.

### Controlled asynchronous invocations

A `ControlledAsyncAction` or `ControlledAsyncGuard` is declared on the edge in place of the real async
callback. The test resumes when the engine has entered it, and the engine resumes when the test answers that
very invocation, so an asynchronous transition is asserted without a single sleep. `awaitInvocation` and
`AsyncAssertions.await` each take a budget that belongs to the test. The fixture is `AutoCloseable`; closing
is idempotent, cancels the stages it created itself and rejects any invocation that arrives afterwards: the
action with a `GraphAssertionError`, an `Error` that neither a recovery rule nor a `failsOn` route can take for
a failure of the action, and the guard with an `IllegalStateException`, which aborts the transition.
`awaitInvocation` on a closed fixture fails at once, and a wait already blocked when the fixture closes is
woken and fails the same way.
Concurrent invocations are independent: each arrives with its own handle and is answered on its own. From
[ControlledAsyncTest](../graphmaquina-test/src/test/java/consumer/ControlledAsyncTest.java), where
`GENEROUS` is that test's own two-second budget:

```java
ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(5), action));
try (registry; action) {
    Entity first = new Entity("first", State.NEW);
    Entity second = new Entity("second", State.NEW);
    CompletableFuture<Outcome<Entity, State, Id>> firstResult =
            registry.of(KEY).async().execute(first, Id.PREPARE);
    CompletableFuture<Outcome<Entity, State, Id>> secondResult =
            registry.of(KEY).async().execute(second, Id.PREPARE);
    PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> one =
            action.awaitInvocation(GENEROUS);
    PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> other =
            action.awaitInvocation(GENEROUS);
    assertNotSame(one, other);
    assertNotSame(one.call().entity(), other.call().entity());
    assertEquals(2, action.invocations().size());

    PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> firstCall =
            one.call().entity() == first ? one : other;
    PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> secondCall =
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

The machine that holds an async edge must declare `asyncTimeout(...)`, as any async machine does. That
deadline is the engine's; the budgets above are the test's, and the two are unrelated — see
[what a check does not prove](#what-a-check-does-not-prove).

### Failure kinds and recovery rules

An edge can declare recovery rules besides its `onFailure` fallback: a rule pairs a failure kind with the
identifier of its recovery edge, and may list the throwable types that select it. The
[guide](guide.md#recover-by-failure-kind-and-exception-type) describes how the engine chooses. A test asserts the
route a call took on the call's outcome, and the rules an edge declares on the definition.

These samples use the publication machine of that guide section. `graph(action)` declares it with `action` on
`PUBLISH`, `asyncGraph(action)` declares it with an asynchronous one, and `machine(graph)` builds a standalone
machine with a five-second `asyncTimeout`.
[TestingGuideFragmentsTest](../graphmaquina-test/src/test/java/consumer/TestingGuideFragmentsTest.java) writes out
those helpers and the publication types, cuts the samples out of this page, compiles them against the published
jars and runs them.

A probe answers three calls in turn: a returned failure with a kind, a declared exception, and a returned failure
without a kind, which the fallback takes:

```java
ServiceUnavailableException down = new ServiceUnavailableException("provider down");
ActionProbe<Publication, PublicationState, PublicationChange> publish =
        ActionProbe.<Publication, PublicationState, PublicationChange>create("publish", CallTrace.create())
                .thenReturn(ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"))
                .thenThrow(down)
                .thenReturn(ActionOutcome.failed("invalid response"));
StateMachine<Publication, PublicationState, PublicationChange> machine = machine(graph(publish));

assertThat(machine.execute(new Publication("p1"), PublicationChange.PUBLISH)).isFailed()
        .hasFailureKind(PublicationFailures.Rejected.class)
        .hasFailureTransition(PublicationChange.RECORD_REJECTION)
        .movedTo(PublicationState.REJECTED_BY_PROVIDER)
        .hasNoCause();
assertThat(machine.execute(new Publication("p2"), PublicationChange.PUBLISH)).isFailed()
        .hasFailureKind(PublicationFailures.Unavailable.class)
        .hasFailureTransition(PublicationChange.SCHEDULE_RETRY)
        .movedTo(PublicationState.RETRY_PENDING)
        .hasCauseSameAs(down);
assertThat(machine.execute(new Publication("p3"), PublicationChange.PUBLISH)).isFailed()
        .hasNoFailureKind()
        .hasFailureTransition(PublicationChange.MARK_INTEGRATION_FAILED)
        .movedTo(PublicationState.INTEGRATION_FAILED)
        .hasReason("invalid response");
```

`hasFailureKind` asserts the kind the failure carried, not the route that took it: a kind that no rule declares
reaches the fallback and keeps its kind. Assert `hasFailureTransition` beside it to pin the route.

The rules belong to the edge's declaration. `recoversExactly` compares them in declaration order, as
`failsOnExactly` compares the edge's own types; the order selects nothing, but the declaration keeps it.
`failsOnExactly` reads only the edge's own `failsOn`, never the types of its rules:

```java
assertThat(machine.definition())
        .transition(PublicationState.READY, PublicationChange.PUBLISH)
        .recoversExactly(List.of(
                new RecoveryRule<>(PublicationFailures.Unavailable.class, PublicationChange.SCHEDULE_RETRY,
                        List.of(ServiceUnavailableException.class)),
                new RecoveryRule<>(PublicationFailures.Rejected.class, PublicationChange.RECORD_REJECTION,
                        List.of(ProviderRejectedException.class))))
        .failsOnExactly(InvalidResponseException.class)
        .hasFailureTransition(PublicationChange.MARK_INTEGRATION_FAILED);
```

An action's result can be asserted on its own, before any machine runs it. On an action result both expectations
require the `Failed` variant, so `ActionOutcome.ok()` fails them:

```java
assertThat(ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider down"))
        .isFailed()
        .hasFailureKind(PublicationFailures.Unavailable.class)
        .hasReason("provider down");
assertThat(ActionOutcome.failed("invalid response")).isFailed().hasNoFailureKind();
```

The same rules route an asynchronous action. A `ControlledAsyncAction` lets the test answer the stage with a
failure or complete it with an exception, and `AsyncAssertions` reads the outcome:

```java
Duration testBudget = Duration.ofSeconds(10);
ControlledAsyncAction<Publication, PublicationState, PublicationChange> publish = ControlledAsyncAction.create();
try (publish) {
    StateMachine<Publication, PublicationState, PublicationChange> machine = machine(asyncGraph(publish));
    CompletableFuture<Outcome<Publication, PublicationState, PublicationChange>> rejected =
            machine.async().execute(new Publication("p4"), PublicationChange.PUBLISH);
    publish.awaitInvocation(testBudget)
            .complete(ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"));
    AsyncAssertions.await(rejected, testBudget).isFailed()
            .hasFailureKind(PublicationFailures.Rejected.class)
            .hasFailureTransition(PublicationChange.RECORD_REJECTION)
            .movedTo(PublicationState.REJECTED_BY_PROVIDER);

    ProviderRejectedException refused = new ProviderRejectedException("content refused");
    CompletableFuture<Outcome<Publication, PublicationState, PublicationChange>> thrown =
            machine.async().execute(new Publication("p5"), PublicationChange.PUBLISH);
    publish.awaitInvocation(testBudget).fail(refused);
    AsyncAssertions.await(thrown, testBudget).isFailed()
            .hasFailureKind(PublicationFailures.Rejected.class)
            .hasFailureTransition(PublicationChange.RECORD_REJECTION)
            .hasCauseSameAs(refused);
}
```

`testBudget` is how long the test waits, not the engine's deadline. The fixture is the only resource of the
try-with-resources header because a standalone machine has no registry to close.

## Goldens and structural snapshots

Machine contracts and visual output have separate tests. Do not use a graphical export to validate machine
logic.

- `GraphSnapshots.assertMatches(definition, resource)` compares declared metadata with a checked-in
  `.graph` file: the machine key including the transition enum, the ordered catalogue of identifiers,
  ordered states, initial state, terminals, async mode, node policies, watches and their ordered rules with
  the identifier each one executes, each sub-machine with its child machine and the identifier each terminal state
  of the child exits through, and for every edge its `(from, id)` key, destination, kind, reason-free
  action and guard names/modes, fallback failure identifier, `failsOn` classes, timeout, after-commit hook and, only
  when the edge declares some, its recovery rules in declaration order, each with its failure kind, recovery
  identifier and `failsOn` classes. That order routes nothing, yet reordering the rules changes the snapshot.
  It reads `GraphDefinition` and its `TransitionSpec`s directly and does not call an exporter. Absence has no
  path at all: an identifier with no edge from a state simply does not appear. Use typed graph assertions
  for focused expectations. Neither approach executes domain logic; test guards, actions, cascades and
  watches through real operations and assert their outcomes, records, reactions and observed entity states.
- `GraphGoldens.assertMatches(definition, resource)` compares the Graphviz DOT output with a checked-in
  `.dot` file. There is no detail mode: the export draws the declared edges and renders each node's policy
  as that node's tooltip. This is a visual regression test only: a diagram change must not be the evidence
  for a change in machine behavior.

Snapshots are `graphmaquina-snapshot-v2`. `GraphSnapshot.parse` refuses a v1 document by name and asks for
it to be regenerated; it is never converted, so a 1.x `.graph` resource is rewritten from the migrated
definition and its diff reviewed. `PublishedArtifactSmokeTest` compiles and runs a semantic consumer after
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

The two directions resolve differently. Read mode never writes, and update mode writes only under the source
root. Nothing compares that root with the class path, so point it at the source directory, never at a
class-path output:

| Mode | Resource resolved through | Written to |
| --- | --- | --- |
| Read (the default) | the class loader, so the copy on the test class path | nothing |
| Update | not read as a golden at all | the source root named by `graphmaquina.goldens.root` |

Two system properties select the mode. `graphmaquina.goldens.update` accepts only the exact values `true`
and `false`; `graphmaquina.goldens.root` names the source root an update writes into and is required when
update mode is on. Read mode ignores it: `GoldenOptions.defaults()` makes it a path only in update mode, so in
read mode `sourceRoot()` is empty and a value that is not a path fails nothing. The resource string is a
class-path-relative path such as `golden/order.dot`, and update mode writes it at that same relative path under
the root, so pointing the root at `src/test/resources` puts the file back where the class path picked it up.

Update mode writes through a symbolic link inside the root, at the golden itself or at a directory above it, and
keeps the link. It does so even when what the link names does not exist yet: it creates that file, with any
directory missing on the way, unless a `.` or `..` follows a missing name in the path the link holds, which
fails with `IllegalStateException`. A link that leads out of the root, a dangling one included, is refused with
`IllegalArgumentException`, and nothing is written.

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
| `hasStatus(APPLIED)` or `hasCode(...)`: what the root call reported | that its reactions committed, or where the entity is now. Read `reactions()` and `hasEntityState(...)` for those. |
| `movedTo(...)` and `hasRecordedState(...)`: the state the outcome recorded | where the entity is now. `hasEntityState(getter, ...)` reads the live entity, and the entity can move again right after. |
| `hasTransitionsExactly(from, ids…)`: which identifiers the state declares | that any of them would succeed for a given entity. It is structural; guards are not evaluated. |
| `isRejected().hasNoTransition()`: nothing was keyed by `(from, id)` | that the entity cannot move at all. Another identifier may well have an edge from the same state. |
| `calls().assertCount(n)`: a callback was entered n times | that the machine wrote a state. Only `StateWriteProbe.writes()` sees the setter. |
| `journal()`: which actions the aborted attempt entered, and how each ended | that the entities were put back. `StateSnapshot.assertUnchanged()` is the expectation that asserts the rollback. |
| the recorder: the outcome of every call that completed | anything about a call that threw, such as one whose step aborts, whose commit fails, whose hook throws, or whose reaction raises an `Error`. The recorder keeps completed calls only; capture the throwable instead. |
| `AsyncAssertions.await(stage, budget)`: the engine's transition finished | that work the action started in the background ended. Neither wait ever cancels a stage it was handed. |
| that budget: how long the test is willing to wait | the engine's deadline. That is `asyncTimeout(...)` on the machine and `hasTimeout(...)` on the edge, and an expired engine deadline leaves the user's stage alive. |

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
- **A reaction completes before the call that triggered it.** A watch reaction is a call nested in the one whose
  commit triggered it: an observer sees the root start, then the reaction start and end, then the root end. The
  recorder keeps calls as they complete, so the reaction's outcome comes before the root's.
- **`hasAsyncAction()` is about the action alone.** An edge whose guard is async but whose action is not still
  satisfies `hasSyncAction()`.
- **A self-loop is asserted like any other edge.** `transition(REVIEW, RETOUCH).hasTarget(REVIEW)` describes
  it, and a request for it produces `Applied`, not `Ignored`. `Ignored` only ever comes from `reevaluate`.
- **Two edges to one state need two expectations.** `transition(from, APPROVE)` and
  `transition(from, AUTO_APPROVE)` are separate subjects even though both `hasTarget` the same state. A test
  that asserts only the destination cannot tell which action ran; assert the action name, or observe it.
- **Entities are selected by reference.** `forEntity(key, entity)` matches on identity, so two entities that
  carry the same id are two different subjects.
- **`hasTerminalsExactly` compares as a set; records, guards and reactions keep order and multiplicity.**
- **Assert on the fields, not the type.** A `GraphAssertionError` carries `context()`, `property()`,
  `expected()` and `actual()`. A test that only checks that something was thrown cannot tell a real
  rejection from an incidental failure. When the actual value is a throwable, it is the error's cause as well,
  so a report shows where it was thrown.

## Maintaining test support

Every GraphMaquina feature or behavior change must provide its counterpart in `graphmaquina-test` in the
same delivery. Extend the relevant assertions, probes, recorded calls or snapshots so a consuming project
can verify the new behavior. Include regression coverage and documented consumer usage. When existing
support already expresses the behavior, demonstrate it with an executable consumer test. Public test APIs
must also be exercised against the staged artifacts by `:graphmaquina-test:publishedArtifactTest`.
