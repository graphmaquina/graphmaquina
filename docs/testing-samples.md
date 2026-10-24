# Testing samples: graphs, outcomes, probes and async

## Contents

- [The graph, its watch and its edges](#the-graph-its-watch-and-its-edges)
- [Outcomes, reactions and observed state](#outcomes-reactions-and-observed-state)
- [Failures and the journal](#failures-and-the-journal)
- [The call recorder](#the-call-recorder)
- [Tracked entity state](#tracked-entity-state)
- [Probes](#probes)
- [Operation input in recorded calls](#operation-input-in-recorded-calls)
- [Controlled asynchronous invocations](#controlled-asynchronous-invocations)

## The graph, its watch and its edges

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
        .isAction().hasActionNamed("restock").hasHookNamed("applyRestock");
assertThat(items.definition())
        .transition(ItemStatus.PACKING, ItemTransition.CANCEL)
        .hasTarget(ItemStatus.CANCELLED)
        .isAction().hasActionNamed("restock").hasHookNamed("applyRestock");
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

## Outcomes, reactions and observed state

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

A guard rejection names the guard, and asserting the name also asserts `RejectionKind.GUARD`. The DSL refuses two
guards of one name on an edge with `SG105`, and so does the annotation processor, so in a graph the DSL built the name
identifies the guard that rejected. A definition assembled without the DSL is not checked for it. A domain failure
keeps the very exception instance the action reported, and names the identifier that recovered:

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

## Failures and the journal

An aborted transition throws rather than returning an outcome, so the subject is the throwable.
`captureFailure` runs the operation and hands back what it threw; it catches no `Error`, so an
`AssertionError` raised inside the body still fails the test the way it should. The order below has a `PACKED`
item, which has no `CANCEL` edge, and a `PENDING` one. From
[OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java):

```java
Throwable failure = captureFailure(() -> orders.cancel(order, "B-7"));

assertThat(failure).isCascadeAbort()
        .hasErrorCount(1).hasPhase(CallPhase.ACTION).hasStatesWritten(StatesWritten.NONE)
        .journal().hasActionsExactly("cancel", "restock")
        .hasStatusesExactly(ActionStatus.THREW, ActionStatus.OK);
assertThat(failure).isCascadeAbort().flow()
        .hasValue(ShopFlow.CANCELLATION, "B-7")
        .hasNoValue(ShopFlow.restockOf(packed))
        .hasValue(ShopFlow.restockOf(pending), new Restock("B-7", order))
        .trail()
        .hasEntryTypesExactly(FlowEntry.Hop.class, FlowEntry.Hop.class, FlowEntry.Failed.class)
        .hasHopsExactly(OrderTransition.CANCEL, ItemTransition.CANCEL)
        .endsWithFailure(CallPhase.ACTION)
        .hops().forEntity(ItemsRuntime.KEY, pending).single()
        .from(ItemStatus.PENDING).hasTransition(ItemTransition.CANCEL).to(ItemStatus.CANCELLED);
assertThat(order, Order::status).isIn(OrderStatus.NEW);
assertThat(packed, Item::status).isIn(ItemStatus.PACKED);
assertThat(pending, Item::status).isIn(ItemStatus.PENDING);
assertEquals(Optional.empty(), packed.restockedIn());
assertEquals(Optional.empty(), pending.restockedIn());
assertEquals(List.of(), order.restocked());
```

The journal lists the actions that ran: `cancel`, which threw the aggregate of its `COLLECT` cascade, and the
`restock` of the pending item, which completed. The flow keeps what the steps wrote, the batch and the pending item's
restock, while its trail holds the order's hop and that item's, then the failure. No hook ran, so no entity holds the
restock: the shop applies it in a hook, as
[set a field only when the call commits](call-flow.md#set-a-field-only-when-the-call-commits) explains.

`isCallFailure()` reads any failure of a call through what every kind shares, and the four call-failure views offer
the same expectations: `hasPhase(...)`, `hasStatesWritten(...)`, `hasCallSameAs(...)`, which compares by reference
with the `MachineCall` an observer received, `journal()`, and `flow()`, which
[the call's flow and its trail](testing-advanced.md#the-calls-flow-and-its-trail) describes.

## The call recorder

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
moving after its call completes, so if the assertion must see the entity as it was, build the recorder with
`TransitionRecorder.projecting((key, outcome) -> ...)` and read what it projected when each call completed:
`recorder.snapshot()` lists the `RecordedTransition`s so far, each with its `sequence`, `key`, `outcome` and
`projection`.

Each outcome carries the call's flow, `outcome.flow()`, so the recorder also retains every flow it records: the
root entity and the values the call's steps and hooks left there, and, for a call whose root machine records its
trail, the entries of the trail, which hold the call's entities and the throwable a failure route took, all by
reference, for as long as the recorder is kept. It stores the outcome beside a projection too, so projecting releases
neither.

## Tracked entity state

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

assertThat(captureFailure(() -> orders.cancel(order, "B-7"))).isCascadeAbort().hasErrorCount(1);

before.assertUnchanged();
```

`assertUnchanged()` fails with every entity that moved, not only the first, so one run names the whole
difference. `changes()` reads every tracked getter again on each call and answers about the entities as they
are now. Each `StateChange` holds the key, the entity and its states `before()` and `after()`, both `Optional`: a
getter that answers null observes no state, which is empty, so a state that appears or disappears is a change like
any other.

## Probes

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

Guard and action probes queue one answer per call, in order, with `thenReturn` and `thenThrow`, and set the standing
answer used once the queue is empty with `otherwiseReturn`; a hook probe queues with `thenSucceed` and `thenThrow` and
sets it with `otherwiseSucceed`. Every probe's `onCall(consumer)` sets the side effect run on each scripted call, with the
live context, or the entity for a hook; a later `onCall` replaces it. A probe that runs out of answers says which probe and
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

## Operation input in recorded calls

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
`hasNoInput()` to check a call that carried no operation argument, since the engine refuses a null one before the
call starts. `CallInputAssert.assertThat(call)` is also available without the `GraphAssertions` facade.

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
published-artifact consumer also compiles and runs these input assertions directly from the staged JARs.

## Controlled asynchronous invocations

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
until each unanswered call gives up at its own deadline, the edge's `timeout` or the call's `asyncTimeout`,
whichever ends first, before the fixture ever gets to cancel it. Calls that run side by side wait out their
deadlines side by side, so two unanswered invocations started together cost about one such wait, not two. Only
the default executors of the machines are awaited: closing the registry does not wait for an executor the
application supplied.

The machine that holds an async edge must declare `asyncTimeout(...)`, as any async machine does. When a call
on a sub-machine can run that edge implicitly, as the exit of its binding or an edge that exit leads to, the
sub-machine's machine must declare one too: the call runs the edge under its own deadline. The same applies to
a caller whose own edge starts a binding with a terminal initial descendant that selects an async exit.
That deadline is the engine's; the budgets above are the test's, and the two are unrelated — see
[what a check does not prove](testing.md#what-a-check-does-not-prove).

---

Next: [Testing samples: recovery, sub-machines and the call's flow](testing-advanced.md). Back to the [documentation index](README.md).
