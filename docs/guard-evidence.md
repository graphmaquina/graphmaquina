# Guard evidence: time, shared reads, typed input and failures

A guard decides from evidence: the entity, the operation's input, the time, an answer of another service. This page
covers what the guard context offers for each, and what the caller receives when reading the evidence fails. Every
example comes from the loan machine of [gates and external edges](edge-kinds.md), whose extension gate reads the due
date and the book's reservations.

## Contents

- [Read the time of the call](#read-the-time-of-the-call)
- [Read evidence once per request](#read-evidence-once-per-request)
- [Read an input of one of several types](#read-an-input-of-one-of-several-types)
- [When reading the evidence fails](#when-reading-the-evidence-fails)
- [Move a check that does I/O into a guard](#move-a-check-that-does-io-into-a-guard)

## Read the time of the call

`ctx.now()` answers the instant of the call on the machine's clock, `InstantSource.system()` unless the builder was
given another one with `clock(InstantSource)`. The clock is read the first time a context of the request asks, and every
later `now()` of the same request answers that instant: the guards and actions of one call, the steps it cascades to and
the checks its guards start all decide at one moment, so two guards never disagree about whether a deadline passed. A
check of its own, a choice check for all its candidates, a watch reaction and each automatic attempt read the clock
again, and a request that never asks reads no clock. The
[loan's guards](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/loan/LoanGuards.java) compare it
with the due date:

```java
@GraphGuard
public GuardResult beforeDue(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
    return ctx.now().isBefore(ctx.entity().dueAt())
            ? GuardResult.pass() : GuardResult.reject("the loan is past due");
}
```

`TransitionContext.now()` answers the same instant to an action. A test hands the set a clock it controls, as the
[loan's flow test](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/loan/LoanFlowTest.java) does to
put a loan past its due date:

```java
return LoanMachinesRuntime.builder(new LoanGuards(reservations)).clock(InstantSource.fixed(now)).build();
```

The trail of a call, when the machine records one, is read on the same clock, as [the call's flow](call-flow.md) says.
Anything but an Error that the clock throws, and a null instant, refused with a `NullPointerException`, is thrown by
`now()` to the guard or action that asked, which fails as it does when it throws that itself.

## Read evidence once per request

The extension gate has two guards that need the book's reservations: one refuses while a reader waits for the book, the
other while a course holds it. Each could call the reservations service; `ctx.memo(key, supplier)` makes them share one
answer. The first guard of the request that asks for the key computes the value with the supplier; every later guard of
the request that asks gets the same value back. The
[loan's guards](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/loan/LoanGuards.java) declare the
key once, as a constant:

```java
public static final MemoKey<ReservationSummary> RESERVATIONS = MemoKey.named("reservations");
```

```java
private ReservationSummary summary(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
    return ctx.memo(RESERVATIONS, () -> reservations.summaryFor(ctx.entity().id()));
}
```

- **One request.** A request is one call, an operation, an `execute`, a choice or an attempt, with the steps it cascades
  to and the checks its guards start, or one check. A choice check is one request for all its candidates, so evidence
  that several candidates share is read once, as [choices](choices.md) recommend. Two calls compute the value twice.
- **Keys by reference.** Two `MemoKey` instances are two keys even when they share a name, which is for messages only.
- **Failures keep nothing.** A supplier that throws is the guard throwing, and a supplier that answers null aborts the
  call, or fails the check, as a guard that returns null does; neither keeps a value.
- **The calling thread only.** Like the rest of the guard context, `memo` works only on the thread that called the
  guard's `test()` and only while the call runs.

## Read an input of one of several types

`ctx.input(Type.class)` reads the operation's input and throws `ClassCastException` when it is of another type. A guard
that serves operations with inputs of different types reads the one it knows with `ctx.inputIfInstance(Type.class)`,
which answers the input when it is an instance of the type and is empty otherwise, without throwing. Both refuse a
primitive class token; use its wrapper class. Actions have the same two methods on `TransitionContext`.

## When reading the evidence fails

A guard that throws, or completes its stage with a failure, aborts the call: the caller receives a
`TransitionAbortedException` in phase `GUARD`, or, from a check, an `EligibilityCheckException`. Both answer
`guardFailure()` with what the guard threw, and `TransitionAbortedException.guard()` names the guard. The engine's own
reasons, such as a null verdict, a timed-out stage or an interrupted wait, carry no guard failure, so `guardFailure()`
is empty for them. [Runtime exceptions](runtime-exceptions.md) lists both accessors and `uninitializedState()`, which
finds a null state the same way.

When the caller should handle the service's failure itself, the guard declares the types it propagates. The
[loan's reservation guards](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/loan/LoanGuards.java)
declare `ReservationsUnavailable`:

```java
@GraphGuard(propagates = ReservationsUnavailable.class)
public GuardResult noWaitingReader(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
    return summary(ctx).waitingReaders() == 0
            ? GuardResult.pass() : GuardResult.reject("a reader is waiting for the book");
}
```

The DSL takes the same list as the third argument of `guard` and `asyncGuard`. When the guard of the root step throws an
instance of a declared type, the call throws that very throwable instead of the abort, once its observers heard of the
failure and its unit of work was discarded, and so does a check. This holds for the calls the application makes, an
operation, `execute`, a choice and the attempt `advance(entity)` makes itself, and for checks. The attempts an automatic
chain makes after a commit and watch reactions still never throw to the caller, and a guard of a step reached by a
cascade still aborts the root, whose `guardFailure()` gives what that guard threw. A guard may not propagate a type that
`failsOn` may not list either, such as `Exception`, `RuntimeException` or an `Error`: the build refuses it as `SG302`.

## Move a check that does I/O into a guard

An application that asks a service before it calls an operation, and refuses the call itself when the service says no,
keeps a rule outside the graph: checks, choices and [eligibility](eligibility.md) answers do not see it, and the call
and the check can disagree. Move the read into a guard of the edge:

1. Write a guard that reads the service, and keep what several guards need with `ctx.memo`.
2. Declare the service's failure in `propagates` when the caller handles it, so the call throws it as it did before.
3. Ask a check, or declare a gate when the question is a permission that moves nothing, instead of calling the service
   before the operation.

---

Next: [Handle transition outcomes](outcomes.md). Back to the [documentation index](README.md).
