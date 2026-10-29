# Check an operation before you call it

A screen that offers an operation, such as an *Approve* button on the page of a request, has to know before the user
clicks whether the machine would admit that operation for that entity now, and why not when it would not: the button
is then enabled, or disabled with the reason. An eligibility check answers from the rules the machine declares. It
runs the admission of the operation, its guards included, and stops before the action, so the application needs
neither a copy of the graph's conditions nor a call made only to see whether it is refused.

## Contents

- [What a check answers](#what-a-check-answers)
- [Check through the facade](#check-through-the-facade)
- [Check through the DSL](#check-through-the-dsl)
- [Read the answer](#read-the-answer)
- [When a check cannot answer](#when-a-check-cannot-answer)
- [What a check runs and never does](#what-a-check-runs-and-never-does)
- [Asynchronous guards and the deadline](#asynchronous-guards-and-the-deadline)
- [Sub-machines](#sub-machines)
- [Concurrency and transactions](#concurrency-and-transactions)
- [Where a check may run](#where-a-check-may-run)
- [Test a check](#test-a-check)

## What a check answers

A check asks about one operation for one entity, with the input that operation takes, and answers with an
`Eligibility<E, S, T>`: `Eligible` when nothing refuses the request from the state the entity is in, or `Ineligible`
with the reason, the `RejectionKind` and, for a guard, the guard's name. It runs the admission of `execute`, in the
same order: the binding check of a sub-machine, the edge keyed by the current state and the identifier, the refusal
of an exit reserved to a sub-machine, then the guards in declaration order up to the first that rejects. Where
`execute` would return a `Rejected` outcome, the check answers `Ineligible` with the same kind, reason and guard;
where `execute` would go past the guards to the action, the check answers `Eligible`.

An answer promises no more than that:

- `Eligible` says what held when the check ran, and reserves nothing. The entity, the states its guards read and the
  parent of a sub-machine can change before the call, and `execute` checks everything again.
- `Eligible` says nothing about what follows the guards. A check runs no action, so it cannot tell whether the action
  would succeed, fail into a failure route or abort, or whether a cascade of the action would be refused. It starts no
  sub-machine and runs no exit, so it cannot tell whether entering a bound state starts its child, or whether the exit
  that ending a child triggers in its parent passes its guards and runs. Nor can it tell whether the commit or an
  after-commit hook would fail.
- Neither answer names a destination. `Rejected` names none, and an `Eligible` that named one would read as a
  promise the check does not make.

Through a generated facade, you check the operations you declare a check for; through a `StateMachine`, any
identifier of the machine's catalogue.

## Check through the facade

A check is an abstract method of the facade that carries the generated `Check`. Its `id` names the identifier of one
of the facade's operations, and the method repeats that operation's parameters, with `Eligibility<E, S, T>` as its
result. The shop's
[Orders](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/order/Orders.java)
declares a check of `retry(Order order)`, which takes no input:

```java
@OrdersGraph.Check(id = OrderTransition.RETRY)
Eligibility<Order, OrderStatus, OrderTransition> canRetry(Order order);
```

and one of `cancel(Order order, String cancellationId)`, whose input is the cancellation identifier:

```java
@OrdersGraph.Check(id = OrderTransition.CANCEL)
Eligibility<Order, OrderStatus, OrderTransition> canCancel(Order order, String cancellationId);
```

The processor refuses a check whose `id` no operation of the facade executes, such as an identifier that only a
failure route declared on the facade itself uses, the exit of a sub-machine, or one without an edge (`SG512`); a
check that does not repeat the parameters of its operation, or returns anything but `Eligibility<E, S, T>`
(`SG513`); and a second check of one operation, or a method that carries both a `Transition` and a `Check`
(`SG514`). [Method signatures](graph-model.md#method-signatures) gives the signature. An operation you declare no
check for cannot be checked through the facade. The generated companion implements each check: it refuses a null
argument with a `NullPointerException` that names the parameter, as the operation does, then calls its
`StateMachine`'s `eligibility(entity, id)`, or `eligibility(entity, id, input)` for an operation that takes an input.

The shop's
[EligibilityTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/EligibilityTest.java)
asks these checks. `RETRY` leaves `PAYMENT_DECLINED` under the guard `retryBudgetLeft`, which passes while the order
has made fewer than three charge attempts. After one declined charge, the order may retry:

```java
Order order = new Order("o1");
gateway.decline("51");
orders.pay(order);

assertThat(orders.canRetry(order)).isEligible()
        .from(OrderStatus.PAYMENT_DECLINED)
        .transition(OrderTransition.RETRY);
```

`from` is the state the check read, and `transition` the identifier it asked about. Once three charges were declined,
the guard refuses, and the answer names it and gives its reason:

```java
Order order = new Order("o1");
gateway.decline("51").decline("51").decline("05");
orders.pay(order);
orders.retry(order);
orders.pay(order);
orders.retry(order);
orders.pay(order);

assertThat(orders.canRetry(order)).isRejectedByGuardNamed("retryBudgetLeft")
        .hasReason("exhausted the 3 charge attempts");
```

`canCancel` takes the input that `cancel` takes, and the guards of a check read it through `ctx.input(...)`, as those
of the call do; `CANCEL` has no guard, so here the input only completes the signature. `CANCEL` leaves `NEW` and
`PAYMENT_DECLINED`, so from `PAID` the check finds no edge and answers with the reason of `PAID`'s policy. The test's
`orderIn` puts a new order in the state it names with `setStatus`; it stands for an order loaded from storage in that
state:

```java
Order fresh = orderIn(OrderStatus.NEW);
Order declined = orderIn(OrderStatus.PAYMENT_DECLINED);
Order paid = orderIn(OrderStatus.PAID);

assertThat(orders.canCancel(fresh, "c-1")).isEligible().from(OrderStatus.NEW);
assertThat(orders.canCancel(declined, "c-1")).isEligible().from(OrderStatus.PAYMENT_DECLINED);
assertThat(orders.canCancel(paid, "c-1")).hasNoTransition()
        .hasReason("money was charged: requires a refund");
```

## Check through the DSL

A `StateMachine`, the machine the DSL builds and the one a generated facade wraps, checks an identifier with
`eligibility(entity, id)`, or with `eligibility(entity, id, input)` for an operation that carries an input:

```java
Eligibility<Order, OrderStatus, OrderTransition> retry =
        machine.eligibility(order, OrderTransition.RETRY);
Eligibility<Order, OrderStatus, OrderTransition> cancel =
        machine.eligibility(order, OrderTransition.CANCEL, "c-1");
```

The first asks whether `execute(order, OrderTransition.RETRY)` would be admitted, and its guards read an empty
`input()`. The second asks about `execute(order, OrderTransition.CANCEL, "c-1")`, and its guards read `"c-1"`. A
`StateMachine` knows nothing of the operations a facade declares, so it answers for any identifier of its catalogue,
as `execute` does: an identifier that only a failure route, a cascade or a watch takes is answered by the edge it
keys from the current state, and the exit of a sub-machine, which no facade can check, is answered as
[sub-machines](#sub-machines) shows. A null argument throws `NullPointerException`, and an identifier of another
catalogue, which only raw types let through, `IllegalArgumentException`, before the check starts. On a
`StateMachine` of your own, the interface's two `eligibility` methods are defaults that throw
`UnsupportedOperationException`; the machines the runtime builds implement them.

## Read the answer

What a check answers, its refusals in the order it meets them, and where each reason comes from:

| Answer | `kind()` | `reason()` | `guard()` | When |
| --- | --- | --- | --- | --- |
| `Ineligible` | `DETACHED` | The library's text `no parent holds it in <NODE>`, with ` or <NODE>` for each other bound state whose binding found a parent | Empty | A sub-machine binding of the entity's machine finds its parent, and no parent holds it. Checked before the edge is looked up |
| `Ineligible` | `NO_TRANSITION` | The reason the state's policy gives the identifier: the one it declares for that identifier, or else its default; for a terminal state that declares no policy, the library's text `terminal state <NAME> accepts no transitions` | Empty | No edge leaves the state with the identifier |
| `Ineligible` | `RESERVED_EXIT` | The library's text `<ID> leaves <STATE> only when its sub-machine ends` | Empty | The identifier is an exit of the sub-machine bound to the state. Checked once the edge is found, before its guards |
| `Ineligible` | `GUARD` | The reason the guard rejected with | The guard's name | A guard of the edge rejected. The guards after it did not run |
| `Eligible` | None | None: `explanation()` is empty | None | The edge exists and every guard of it passed |

These are the kinds and the reasons of an `Outcome.Rejected`, so one mapping translates an answer and a refused call
alike. Both variants report `entity()`, `from()`, the state the check read, and `transition()`, the identifier it
asked about; `isEligible()` tells them apart, `explanation()` gives the reason of an `Ineligible` answer and is empty
for an `Eligible` one, and `asEligible()` and `asIneligible()` return the answer as that variant, or empty.

A backend that prepares the screen of an order can turn the answer into the state of a button. This one shows the
reasons the graph declares, a policy's or a guard's, and a text of its own in place of the library's fixed reasons of
a sub-machine:

```java
record ButtonState(boolean enabled, String hint) {

    static ButtonState of(Eligibility<Order, OrderStatus, OrderTransition> answer) {
        return switch (answer) {
            case Eligibility.Eligible<Order, OrderStatus, OrderTransition> eligible ->
                    new ButtonState(true, "");
            case Eligibility.Ineligible<Order, OrderStatus, OrderTransition> refused ->
                    new ButtonState(false, hintFor(refused));
        };
    }

    static String hintFor(Eligibility.Ineligible<Order, OrderStatus, OrderTransition> refused) {
        return switch (refused.kind()) {
            case NO_TRANSITION, GUARD -> refused.reason();
            case DETACHED, RESERVED_EXIT -> "not available for this order now";
        };
    }
}
```

Neither `switch` has a `default`: both are exhaustive, so a variant or a kind that a later release adds fails the
build instead of falling through.

## When a check cannot answer

A check that cannot reach an answer throws an `EligibilityCheckException`, never an `Ineligible` answer: a technical
failure is no refusal. It throws one when reading the entity's identity or state fails, when the binding check of a
sub-machine fails, as when a `parentOf` or a `childOf` throws or returns null, when two parents hold the child or
when a `childOf` answers another instance with the child's identity, and when a guard throws, returns null, or returns
a stage that is null, that fails or that answers null. A wait for an asynchronous guard that times out or is
interrupted, as [the deadline](#asynchronous-guards-and-the-deadline) shows, or that the thread's `ForkJoinPool`
refuses to let block, throws one too. Its cause is what your code threw, unwrapped from `CompletionException` and
`ExecutionException`, or the engine's own exception, such as an `UninitializedStateException` for a null state, an
`AmbiguousParentException` for two parents, an `AmbiguousIdentityException` for another instance with the child's
identity or a `RejectedExecutionException` for the pool's refusal; it has none when your code returned null where the
engine needed a guard's verdict or stage, or a `parentOf` or `childOf` answer.

`key()`, `entity()` and `transition()` name the machine, the entity and the identifier the check asked about, and the
`Optional`s `from()` and `guard()` the state it read and the guard it was running, when it knew them. Its message
reads `eligibility of <ID> for <key>#<identity> <FROM> could not be decided: <reason>`, with the key alone when the
identity could not be read and `?` for a state not read. It is no `CallFailedException`: a check is not a call, so it
has no call, phase, states written, journal or flow. An `Error` propagates as itself, and so do the refusals before a
check starts: a null argument, an identifier of another catalogue, and a check made
[where no check may run](#where-a-check-may-run).

A screen can tell such a failure apart from a refusal:

```java
static ButtonState buttonFor(
        StateMachine<Order, OrderStatus, OrderTransition> machine,
        Order order) {
    try {
        return ButtonState.of(machine.eligibility(order, OrderTransition.RETRY));
    } catch (EligibilityCheckException failure) {
        System.err.println(failure.getMessage());
        return new ButtonState(false, "cannot tell now: try again later");
    }
}
```

[Runtime exceptions](runtime-exceptions.md) lists `EligibilityCheckException` with the others.

## What a check runs and never does

![The decisions of an operation, with a band for an eligibility check: it runs the binding check, the edge lookup, the reserved exit and the guards, in that order, answers Ineligible with the kind of the first refusal, and answers Eligible once every guard passed, before the action. The action and what follows it run in a call only.](images/operation-decisions.svg)

Whatever it answers or throws, a check:

- writes no state: it calls no setter;
- runs no action, so it cascades to no peer, and a guard's context offers no cascade;
- starts or restarts no sub-machine, and runs no exit;
- runs no after-commit hook, dispatches no watch and notifies no observer;
- hands back no records, reactions, starts or flow: an `Eligibility` carries none;
- logs none of the lines of a call. It logs `check=<id>` lines of its own, as
  [what a check logs](logs.md#what-a-check-logs) shows.

The code of yours that the admission calls does run: the state getter and the identity function of the entity; for
the child of a sub-machine, each binding's `parentOf` and `childOf` and the parent's identity and state; and the
guards, synchronous and asynchronous, with what they read: the check's `input()`, peer views, which read the current
state of a peer, and a `flow()` that holds no value. A guard decides, it does not mutate: GraphMaquina neither
prevents nor undoes what these functions do, so keep them free of side effects. An asynchronous guard that asks a
remote service asks it for every check. A context that a guard keeps is stale once the check ends.

In the shop's
[EligibilityTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/EligibilityTest.java),
checking `PAY`, whose action charges the gateway and counts the attempt, and `RETRY`, with the same `orderIn`,
charges nothing, counts nothing and moves nothing; the last call shows that the gateway does record a charge when one
is made:

```java
Order declined = orderIn(OrderStatus.PAYMENT_DECLINED);
Order fresh = orderIn(OrderStatus.NEW);

assertThat(orders.canRetry(declined)).isEligible();
assertThat(orders.canPay(fresh)).isEligible();

assertEquals(List.of(), gateway.charged);
assertEquals(0, declined.paymentAttempts());
assertEquals(0, fresh.paymentAttempts());
assertThat(declined, Order::status).isIn(OrderStatus.PAYMENT_DECLINED);
assertThat(fresh, Order::status).isIn(OrderStatus.NEW);

assertThat(orders.pay(fresh)).isApplied();
assertEquals(List.of(fresh), gateway.charged);
```

## Asynchronous guards and the deadline

A check awaits each asynchronous guard before the next guard runs, as a call does, and the thread that asked waits
with it. The wait is bounded by the machine's `asyncTimeout`, counted from the start of the check, as a call's
[deadline](async-deadlines.md#deadline-rules) is counted from the start of the call. A wait past it fails the check
with an `EligibilityCheckException` whose cause is a `TimeoutException`. A wait that is interrupted fails it with an
`InterruptedException` as its cause, and the thread stays interrupted. A check runs no action, so no action's
`@Timeout` plays a part.

To keep the asking thread free, run the check on the machine's executor:

```java
CompletableFuture<Eligibility<Order, OrderStatus, OrderTransition>> pending =
        machine.supplyAsync(() -> machine.eligibility(order, OrderTransition.RETRY));
```

A facade's `async(...)` runs its supplier through `supplyAsync`, so `orders.async(() -> orders.canRetry(order))` does
the same through the facade.

## Sub-machines

A check refuses the child of a sub-machine where `execute` would, through a facade too. The billing's
[Payments](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/billing/Payments.java)
declares a check of `capture`:

```java
@PaymentsGraph.Check(id = PaymentTransition.CAPTURE)
Eligibility<Payment, PaymentStatus, PaymentTransition> canCapture(Payment payment);
```

[BookingFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/billing/BookingFlowTest.java)
checks a deposit while its booking holds it in `DEPOSIT`, and again once the booking is cancelled, when no parent
holds it: the check says so before it looks for an edge, as the call does.

```java
Booking booking = booking("b4");
Payment deposit = booking.deposit().orElseThrow();
bookings.book(booking);

assertThat(payments.canCapture(deposit)).isEligible().from(PaymentStatus.AUTHORIZING);

bookings.cancel(booking);

assertThat(payments.canCapture(deposit)).isDetached()
        .hasReason("no parent holds it in DEPOSIT or BALANCE");
```

`RESERVED_EXIT` is reached through `StateMachine.eligibility` only. On the parent's machine, from a state that binds a
sub-machine, an identifier that one of its exits names, such as `DEPOSIT_PAID` from `DEPOSIT`, is checked as
`RESERVED_EXIT`, with the reason `DEPOSIT_PAID leaves DEPOSIT only when its sub-machine ends`. A facade can neither
execute an exit, which `SG804` refuses as an operation, nor check one, which `SG512` refuses.

A check starts no child and runs no exit. Asked about an edge that enters a bound state, it answers without starting
the child or calling that binding's `childOf`: when the start fails, the call aborts although the check answered
`Eligible`. Asked about the capture that would end a held child, it answers `Eligible`, and the parent stays in the
bound state, where the call would run its exit; the check evaluates none of that exit's guards, and when one of them
refuses, the call aborts although the check answered `Eligible`. When two parents hold the child, the check throws an
`EligibilityCheckException` whose cause is the `AmbiguousParentException` a call would abort with.
[Interrupt, detach and reserve](composition.md#interrupt-detach-and-reserve) describes both refusals.

## Concurrency and transactions

A check takes no lock and reserves nothing: it reads the entity, and what its guards read, when it runs, and its
unit of work never commits. GraphMaquina serializes no checks and no calls on one entity, so an answer can be out of
date by the time the user acts on it: the call checks everything again, and is refused if a refusal holds by then.
Serializing the calls of one entity, and the transaction a guard reads a database in, are yours to provide, as
[concurrency, identity and persistence](commit-and-persistence.md#concurrency-identity-and-persistence) says for a
call.

## Where a check may run

A check may run where a call may start: outside any call, in an after-commit hook, which runs once the call that
committed has released the thread, and on the machine's executor through `supplyAsync` or a facade's `async`. Inside
a call, where the call runs code of yours on its thread, such as a guard, an action, a watch's owner lookup or an
observer, a check is refused before it starts with `ReentrantTransitionException`, as `execute` is, and logs
nothing.

While a check runs, the code it calls may not start a call or a check on its thread either: `execute`, `reevaluate`,
`advance` or `eligibility` called from a guard, the state getter or a `parentOf` of the check throws
`ReentrantTransitionException`. When that code lets the exception through, the check fails with an
`EligibilityCheckException` whose cause it is.

## Test a check

`GraphAssertions.assertThat(eligibility)`, in `graphmaquina-test`, reads an answer, as the examples above do:
`isEligible()` or `isIneligible()`; of a refusal, `hasNoTransition()`, `isRejectedByGuardNamed(name)`,
`isDetached()`, `isReservedExit()` and `hasReason(reason)`; of both, `from(state)` and `transition(id)`.
[What each entry point checks](testing.md#what-each-entry-point-checks) lists it with the other views.

---

Next: [Programmatic DSL](dsl.md). Back to the [documentation index](README.md).
