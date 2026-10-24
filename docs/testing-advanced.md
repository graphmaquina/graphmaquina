# Testing samples: recovery, sub-machines and the call's flow

## Contents

- [Failure kinds and recovery rules](#failure-kinds-and-recovery-rules)
- [Sub-machines](#sub-machines)
- [The call's flow and its trail](#the-calls-flow-and-its-trail)

## Failure kinds and recovery rules

An edge can declare recovery rules besides its `onFailure` fallback: a rule pairs a failure kind with the
identifier of its recovery edge, and may list the throwable types that select it. The
[guide](actions-and-failures.md#recover-by-failure-kind-and-exception-type) describes how the engine chooses. A test asserts the
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

## Sub-machines

A state can hold a sub-machine, which the [programmatic DSL](dsl.md) declares with
`submachine(...)`. These samples use an order whose `PAYING` state holds its payment. `orders()` declares the order
graph: its binding reaches the payment of an order and the order of a payment, and the payment's terminal states
`CAPTURED` and `DECLINED` exit through `PAY` and `REFUSE`. `ORDERS` and `PAYMENTS` are the keys of the two machines.
[TestingGuideFragmentsTest](../graphmaquina-test/src/test/java/consumer/TestingGuideFragmentsTest.java) writes out
those helpers and types, and the ones named below, cuts the samples out of this page, compiles them against the
published jars and runs them.

### The binding of a state

`submachine(state, childKey)` narrows to the binding of one state. The view is typed by the child's key, so the
compiler accepts only a state of the child as a terminal state and only an identifier of the parent as its exit:

```java
assertThat(orders())
        .hasNoSubmachine(OrderState.CART)
        .submachine(OrderState.PAYING, PAYMENTS)
        .hasExitsExactly(exit(PaymentState.CAPTURED, OrderChange.PAY),
                exit(PaymentState.DECLINED, OrderChange.REFUSE))
        .hasExit(PaymentState.DECLINED, OrderChange.REFUSE);
```

`exit` is the static factory of `ExitExpectation`. `hasExitsExactly` compares the exits in declaration order. Each
expectation of the view first checks that the state holds the child of that key, so a binding of another child fails
on the property `child`, and a state that holds no sub-machine fails as a view without a subject. None of them runs
the binding's lookups.

### Starts and exits

`registry(orders)` registers the order machine built from `orders` and the payment machine, each with a five-second
`asyncTimeout`. An outcome's `starts()` holds the sub-machines its call started. `forChild` selects them by the child's
key and identity, as `records().forEntity` does, and a child can start more than once in one call, so the selection
is read with `single()` or `atIndex(i)`. A start is no hop: when a later call ends the child, the exit is one more
record of that call:

```java
Payment payment = new Payment("p1");
Order order = new Order("o1", payment);
try (MachineRegistry registry = registry(orders())) {
    assertThat(registry.of(ORDERS).execute(order, OrderChange.CHECKOUT)).isApplied()
            .movedTo(OrderState.PAYING)
            .starts().forChild(PAYMENTS, payment).single()
            .startedAt(PaymentState.AUTHORIZING)
            .hadPrevious(PaymentState.AUTHORIZING)
            .byParent(ORDERS, order, OrderState.PAYING)
            .atDepth(0);

    assertThat(registry.of(PAYMENTS).execute(payment, PaymentChange.CAPTURE)).isApplied()
            .records().forEntity(ORDERS, order).single()
            .hasTransition(OrderChange.PAY).to(OrderState.PAID).hasDepth(1);
}
```

`hadPrevious` is the child's state before the start, here the payment's own initial state. A call whose hook threw
after its states were written keeps its starts too: `isAfterCommit().starts()` reads them.

### Refused requests and an ambiguous parent

A sub-machine refuses two kinds of request, and the rejected view names each kind. A child whose parent is no longer in
the bound state is detached, and an exit requested on the parent is reserved, since only the end of the child runs it:

```java
Payment payment = new Payment("p2");
Order order = new Order("o2", payment);
try (MachineRegistry registry = registry(orders())) {
    registry.of(ORDERS).execute(order, OrderChange.CHECKOUT);
    assertThat(registry.of(ORDERS).execute(order, OrderChange.PAY)).isRejected()
            .isReservedExit()
            .hasReason("PAY leaves PAYING only when its sub-machine ends");

    registry.of(ORDERS).execute(order, OrderChange.ABANDON);
    assertThat(registry.of(PAYMENTS).execute(payment, PaymentChange.CAPTURE)).isRejected()
            .isDetached()
            .hasReason("no parent holds it in PAYING");
}
```

A payment has one active parent at a time. `invoices()` declares an invoice whose `SETTLING` state holds its payment
too, and `registryWithInvoices(orders)` registers the invoice machine beside the other two. Once an order and an
invoice both hold the same payment in their bound states, a call to the payment cannot tell whose exit to run: it
throws before any edge is looked up, and `captureFailure` hands back what it threw:

```java
Payment payment = new Payment("p4");
Order order = new Order("o4", payment);
Invoice invoice = new Invoice("i4", payment);
try (MachineRegistry registry = registryWithInvoices(orders())) {
    registry.of(ORDERS).execute(order, OrderChange.CHECKOUT);
    registry.of(INVOICES).execute(invoice, InvoiceChange.SETTLE);
    assertThat(captureFailure(() -> registry.of(PAYMENTS).execute(payment, PaymentChange.CAPTURE))).isAbort()
            .hasPhase(CallPhase.RESOLVE)
            .hasStatesWritten(StatesWritten.NONE)
            .hasCause(AmbiguousParentException.class);
}
assertThat(payment, (Payment value) -> value.state).isIn(PaymentState.AUTHORIZING);
```

The abort's phase is `RESOLVE` because the payment's call is the root; inside a cascade or an exit it would be
`ACTION`.

### Probes on an exit

An exit is an edge of the parent, so the probes stand on it as on any edge. `orders(pay)` declares the order graph with
`pay` applied to the declaration of the exit `PAY`, and `registry(orders, observer)` adds an observer to the registry.
The exit's guard, action and hook run in the payment's call, the action one level deeper and without input. A
`TransitionRecorder` counts that call once, so the checkout and the capture are its two calls, and the exit is a record
of the second:

```java
CallTrace trace = CallTrace.create();
GuardProbe<Order, OrderState, OrderChange> inStock =
        GuardProbe.<Order, OrderState, OrderChange>create("inStock", trace).otherwiseReturn(GuardResult.pass());
ActionProbe<Order, OrderState, OrderChange> confirm =
        ActionProbe.<Order, OrderState, OrderChange>create("confirm", trace).otherwiseReturn(ActionOutcome.ok());
HookProbe<Order> receipt = HookProbe.<Order>create("receipt", trace).otherwiseSucceed();
TransitionRecorder recorder = TransitionRecorder.create();
Payment payment = new Payment("p5");
Order order = new Order("o5", payment);
try (MachineRegistry registry = registry(orders((TransitionBuilder<Order, OrderState, OrderChange> pay) ->
        pay.guard("inStock", inStock).action("confirm", confirm).afterCommit("receipt", receipt)), recorder)) {
    registry.of(ORDERS).execute(order, OrderChange.CHECKOUT);
    registry.of(PAYMENTS).execute(payment, PaymentChange.CAPTURE);
}
trace.assertNamesExactly("inStock", "confirm", "receipt");
assertThat(confirm.calls().single()).hasNoInput();
recorder.assertRecorded()
        .hasSize(2)
        .hasKeysExactly(ORDERS, PAYMENTS)
        .forEntity(PAYMENTS, payment).single()
        .isApplied().records().forEntity(ORDERS, order).single()
        .hasTransition(OrderChange.PAY).hasActionNamed("confirm").hasDepth(1);
```

### An asynchronous exit

An exit can run an asynchronous action. `asyncOrders(pay)` declares the same order graph with `pay` as the
asynchronous action of `PAY`, so a capture waits for that action before it writes anything. A `ControlledAsyncAction`
holds the wait open without a sleep: while the exit is pending neither entity has moved, and once the test answers,
the call commits the payment and the order together:

```java
Duration testBudget = Duration.ofSeconds(10);
ControlledAsyncAction<Order, OrderState, OrderChange> pay = ControlledAsyncAction.create();
Payment payment = new Payment("p3");
Order order = new Order("o3", payment);
try (MachineRegistry registry = registry(asyncOrders(pay)); pay) {
    registry.of(ORDERS).execute(order, OrderChange.CHECKOUT);
    StateSnapshot before = StateSnapshot.builder()
            .track(ORDERS, order, (Order value) -> value.state)
            .track(PAYMENTS, payment, (Payment value) -> value.state)
            .capture();
    CompletableFuture<Outcome<Payment, PaymentState, PaymentChange>> captured =
            registry.of(PAYMENTS).async().execute(payment, PaymentChange.CAPTURE);
    PendingInvocation<ActionCall<Order, OrderState, OrderChange>, ActionOutcome> pending =
            pay.awaitInvocation(testBudget);
    before.assertUnchanged();

    pending.complete(ActionOutcome.ok());
    AsyncAssertions.await(captured, testBudget).isApplied().movedTo(PaymentState.CAPTURED)
            .records().forEntity(ORDERS, order).single().hasTransition(OrderChange.PAY);
    assertThat(order, (Order value) -> value.state).isIn(OrderState.PAID);
}
```

The payment's machine declares an `asyncTimeout`, as
[controlled asynchronous invocations](testing-samples.md#controlled-asynchronous-invocations) explains, because its call runs the
order's asynchronous exit. That deadline is the engine's; `testBudget` is how long the test waits.

### A child over its parent's own entity

A sub-machine can also govern a second state of its parent's own entity, as the
[guide](composition.md#sub-machines-over-the-same-entity) explains. Here the order charges itself: `charge` is a second
state of `Order`, which the payment graph governs under the key `CHARGES`. `chargedOrders()` declares the order graph
with `PAYING` bound to that graph through `submachine(state, child)`, and `chargeRegistry()` registers both machines,
each with the accessors of its own field. A `StateSnapshot` tracks the one order under both keys, so a refused request
leaves both of its states unchanged:

```java
Order order = new Order("o7", new Payment("p7"));
try (MachineRegistry registry = chargeRegistry()) {
    registry.of(ORDERS).execute(order, OrderChange.CHECKOUT);
    StateSnapshot before = StateSnapshot.builder()
            .track(ORDERS, order, (Order value) -> value.state)
            .track(CHARGES, order, (Order value) -> value.charge)
            .capture();
    assertThat(registry.of(ORDERS).execute(order, OrderChange.PAY)).isRejected().isReservedExit();
    before.assertUnchanged();

    assertThat(registry.of(CHARGES).execute(order, PaymentChange.CAPTURE)).isApplied()
            .records().forEntity(ORDERS, order).single().hasTransition(OrderChange.PAY).hasDepth(1);
}
assertThat(order, (Order value) -> value.state).isIn(OrderState.PAID);
assertThat(order, (Order value) -> value.charge).isIn(PaymentState.CAPTURED);
```

Each key reads its own field, so `changes()` reports a moved state under its own key, even for one entity.

## The call's flow and its trail

`flow()` opens [the call's flow](call-flow.md) on every outcome view and on the view of the failure a call
throws; a failure of the call nested in it, such as `error(i)` of a cascade abort, holds the empty flow.
`hasEntitySameAs` compares the entity of the root call by reference, `hasValue` compares the value of a key with
`equals`, `hasNoValue` expects no value under a key, and `hasKeysExactly` compares the keys that hold a value, in the
order of the writes that set them. A key that holds no value fails `hasValue` with `Optional.empty` as its actual
value, and a key that holds another value with `Optional[...]` of that value.

`trail()` opens the trail, which a call records when its root machine was built with
[`recordTrail`](call-flow.md#record-the-calls-trail). `hasEntryTypesExactly` compares the class of every entry, in order.
`hasHopsExactly` compares the identifier of every hop, in order, and skips the sub-machine starts and the final
rejection or failure, so with no argument it expects a trail without a hop. `hops()` opens the records of those hops,
in the view that `records()` opens on an outcome: `forEntity(key, entity)` and `containsExactly(records)` read which
machine and entity took each hop and from which state to which, on the trail of a call that failed before its hooks
too, whose exception lists no `records()`.
`isInTimeOrder` checks that no entry's `at()` is earlier than
the one of the entry before it, so equal instants pass. `endsWithRejection(kind)` and `endsWithFailure(phase)` read
the final entry. Build the machine on a clock the test controls.

These samples use the `graph(action)` helper, the publication types and their machine key `KEY` of
[failure kinds and recovery rules](#failure-kinds-and-recovery-rules), and build their machine here.
[TestingGuideFragmentsTest](../graphmaquina-test/src/test/java/consumer/TestingGuideFragmentsTest.java) writes out
that helper and those types, cuts the samples out of this page, compiles them against the published jars and runs
them, the four in one method.

The action of `PUBLISH` puts a receipt into the call's flow, and throws for a publication whose id starts with `x`.
Each call has a flow of its own: the second call, rejected because the article is already published, finds no
receipt, and its trail holds the rejection alone:

```java
FlowKey<String> receipt = FlowKey.of("receipt", String.class);
StateMachine<Publication, PublicationState, PublicationChange> traced = StateMachines.from(graph(
                (TransitionContext<Publication, PublicationState, PublicationChange> ctx) -> {
                    ctx.flow().put(receipt, "receipt of " + ctx.entity().id);
                    if (ctx.entity().id.startsWith("x")) {
                        throw new IllegalStateException("the outbox is closed");
                    }
                    return ActionOutcome.ok();
                }))
        .state((Publication publication) -> publication.state,
                (Publication publication, PublicationState state) -> publication.state = state)
        .recordTrail(InstantSource.fixed(Instant.parse("2026-10-10T09:00:00Z")))
        .build();
Publication article = new Publication("p6");

assertThat(traced.execute(article, PublicationChange.PUBLISH)).isApplied().flow()
        .hasEntitySameAs(article)
        .hasValue(receipt, "receipt of p6")
        .hasKeysExactly(receipt)
        .trail()
        .hasEntryTypesExactly(FlowEntry.Hop.class)
        .hasHopsExactly(PublicationChange.PUBLISH)
        .isInTimeOrder();
assertThat(traced.execute(article, PublicationChange.PUBLISH)).isRejected().flow()
        .hasNoValue(receipt)
        .trail()
        .hasHopsExactly()
        .endsWithRejection(RejectionKind.NO_TRANSITION);
```

A call that throws hands its flow out on its failure. The action throws a type its edge does not declare, so its
step aborts and nothing rolls it back: the flow keeps the receipt the action put before it threw, and the trail ends
with the failure. Its hop, read through `hops()`, says where the
aborted step was going:

```java
Publication refused = new Publication("x1");
assertThat(captureFailure(() -> traced.execute(refused, PublicationChange.PUBLISH))).isAbort()
        .hasPhase(CallPhase.ACTION)
        .flow()
        .hasEntitySameAs(refused)
        .hasValue(receipt, "receipt of x1")
        .trail()
        .hasEntryTypesExactly(FlowEntry.Hop.class, FlowEntry.Failed.class)
        .hasHopsExactly(PublicationChange.PUBLISH)
        .endsWithFailure(CallPhase.ACTION)
        .hops().forEntity(KEY, refused).single()
        .from(PublicationState.READY).hasTransition(PublicationChange.PUBLISH).to(PublicationState.PUBLISHED);
```

`assertThat` also takes a flow on its own, such as the flow of a `CommitContext` built with its five-argument
constructor to call a hook by hand. That flow belongs to no call: it holds what the hook wrote, no entity and no
trail:

```java
HookRef<Publication, PublicationState, PublicationChange> announce = new HookRef<>("announce",
        (Publication publication, CommitContext<PublicationState, PublicationChange> commit) ->
                commit.flow().put(receipt, "announced " + publication.id));
CommitContext<PublicationState, PublicationChange> commit = new CommitContext<>(PublicationState.READY,
        PublicationChange.PUBLISH, PublicationState.PUBLISHED, TransitionKind.ACTION, true);
announce.run(new Publication("p7"), commit);

assertThat(commit.flow()).hasValue(receipt, "announced p7").hasKeysExactly(receipt).trail().isEmpty();
```

A watch reaction is a call of its own: `isDone().outcome().flow()` reads the reaction's flow. A reaction whose
reevaluation threw has that reevaluation's `CallFailedException` as its cause, which hands out the reaction's own
flow and which `assertThat(cause).isCallFailure().flow()` reads; a reaction whose owner lookup threw ran no call.

A trail held as a list, such as the one `trail()` of a flow returns, has its own factory:

```java
List<FlowEntry> entries = traced.execute(new Publication("p8"), PublicationChange.PUBLISH).flow().trail();
TrailAssert.assertThat(entries).hasSize(1).hasHopsExactly(PublicationChange.PUBLISH).isInTimeOrder();
```

The shop's [OrderFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/OrderFlowTest.java)
asserts the flow and the trail of a cancellation that commits, and those of one that aborts, which
[failures and the journal](testing-samples.md#failures-and-the-journal) shows. Its set is built with `recordTrail()`, on the system
clock, so it asserts the entries of a trail and their order, never their instants.

---

Next: [Building with the typed graph API](typed-annotations-build.md). Back to the [documentation index](README.md).
