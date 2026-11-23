# Choose the first admitted transition

A carrier reports what became of a shipment: it was delivered, or it went back to its sender. The application receives
the report and has to move the shipment by the edge the report means, which the guards of those edges already decide.
With one operation per edge, the application would repeat those guards in an `if` of its own, or call the operations
one after the other and set the rejections aside. A choice does it in one call: it tries the identifiers it names, in
their order, and runs the first one admitted, with the caller's input.

## Contents

- [When to use a choice](#when-to-use-a-choice)
- [Declare a choice](#declare-a-choice)
- [What a choice returns](#what-a-choice-returns)
- [Check a choice](#check-a-choice)
- [The rules the build checks](#the-rules-the-build-checks)
- [After the commit](#after-the-commit)
- [Test a choice](#test-a-choice)

## When to use a choice

Declare one operation per identifier when the caller knows which edge it asks for, such as a screen with a button for
each. Declare a choice when the data of the call picks among several edges and their guards already say which one
applies: the caller hands over the data, and the machine picks. The identifiers a choice tries, its candidates, stay
ordinary edges, and each may also have an operation of its own.

An [automatic transition](automatic-transitions.md) also runs the first admitted of several edges, but its attempt
carries no input and runs after a commit or on `advance(entity)`, so its guards decide from the entity's fields. A
choice is a call that the application makes, now, with an input that the guards of its candidates read.

## Declare a choice

The shipment machine of the example module,
[Shipments](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/shipment/Shipments.java), declares
`DELIVER` and `RETURN` on the interface, without an operation, and a choice `observe` over them, with its check
`canObserve`:

```java
@GenerateGraphApi
@ShipmentsGraph.Definition(initial = IN_TRANSIT, terminals = {DELIVERED, RETURNED},
        state = ShipmentRefs.Status.class, id = ShipmentRefs.Id.class)
@ShipmentsGraph.StatePolicy(state = IN_TRANSIT, otherwise = Shipments.IN_TRANSIT_POLICY)
@ShipmentsGraph.StatePolicy(state = DELIVERED, otherwise = Shipments.DELIVERED_POLICY)
@ShipmentsGraph.StatePolicy(state = RETURNED, otherwise = Shipments.RETURNED_POLICY)
@ShipmentsGraph.Transition(id = DELIVER, from = IN_TRANSIT, to = DELIVERED, guards = ShipmentGuardsRefs.Delivered.class)
@ShipmentsGraph.Transition(id = RETURN, from = IN_TRANSIT, to = RETURNED, guards = ShipmentGuardsRefs.Returned.class)
public interface Shipments extends Machine<Shipment, ShipmentStatus, ShipmentTransition> {

    /** Runs the first of RETURN and DELIVER that the report admits, or returns Ignored when it admits neither. */
    @ShipmentsGraph.Choice({RETURN, DELIVER})
    Outcome<Shipment, ShipmentStatus, ShipmentTransition> observe(Shipment shipment, CarrierReport report);

    /** Which of RETURN and DELIVER {@code observe} would run for the report now, without running it. */
    @ShipmentsGraph.Choice({RETURN, DELIVER})
    ChoiceEligibility<Shipment, ShipmentStatus, ShipmentTransition> canObserve(Shipment shipment, CarrierReport report);

    String IN_TRANSIT_POLICY = "a shipment in transit is delivered or returned";
    String DELIVERED_POLICY = "the shipment was delivered";
    String RETURNED_POLICY = "the shipment went back to its sender";
}
```

A choice is an abstract method of the facade that carries the generated `Choice`, whose value lists the candidates in
the order they are tried. It carries no `Transition` and no `Check`, and its result decides its role: a method that
returns `Outcome<E, S, T>` runs the choice, and one that returns `ChoiceEligibility<E, S, T>` checks it. Each takes the
entity and may take an input, which the guards of the candidates read, and so does the action of the one that runs.
The two are independent: a choice needs no check, and a check needs no choice.
[Method signatures](graph-model.md#method-signatures) gives both. The generated companion
implements each: it refuses a null argument with a `NullPointerException` that names the parameter, then calls its
`StateMachine`'s `choose(shipment, List.of(RETURN, DELIVER), report)`, or `chooseEligibility` with the same arguments
for the check, without the input for a method that takes none.

The guards of the candidates, in
[ShipmentGuards](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/shipment/ShipmentGuards.java),
read the carrier's report through `ctx.input(...)`, as the guards of an operation read its input:

```java
@GraphGuard
public static GuardResult delivered(GuardContext<Shipment, ShipmentStatus, ShipmentTransition> ctx) {
    return reports(ctx, "delivered") ? GuardResult.pass() : GuardResult.reject("the carrier reported no delivery");
}

@GraphGuard
public static GuardResult returned(GuardContext<Shipment, ShipmentStatus, ShipmentTransition> ctx) {
    return reports(ctx, "returned") ? GuardResult.pass() : GuardResult.reject("the carrier reported no return");
}

private static boolean reports(GuardContext<Shipment, ShipmentStatus, ShipmentTransition> ctx, String event) {
    return ctx.input(CarrierReport.class).map(CarrierReport::event).filter(event::equals).isPresent();
}
```

A `StateMachine`, the machine the DSL builds and the one a facade wraps, runs a choice with `choose(entity, list)` or
`choose(entity, list, input)`, and checks one with `chooseEligibility(entity, list)` or
`chooseEligibility(entity, list, input)`. Before the call or the check starts, and with nothing logged, it refuses a
null argument or candidate with `NullPointerException`, and an empty list, a candidate listed twice or one of another
catalogue, which only raw types let through, with `IllegalArgumentException`. On a `StateMachine` of your own, these
four methods are defaults that throw `UnsupportedOperationException`; the machines the runtime builds implement them.

## What a choice returns

A choice is one call. Each candidate, in order, gets the admission that an `execute` of its identifier with the same
input would get: the binding check of a sub-machine; the edge keyed by the entity's state and the candidate, which
refuses a candidate that no edge keys as `NO_TRANSITION`, with the reason that `execute` would be rejected with;
the refusal of an exit reserved to a sub-machine; then its guards in declaration order, up to the first that rejects.
A refused candidate passes to the next one. The first one admitted runs within the same call as `execute` runs an
edge, without its guards again: its action, its failure routes, its cascades, the starts and exits of sub-machines,
the commit, the hooks and the watch reactions. Its outcome is:

- `Applied` or `Failed`, whose `requestedTransition()` is the candidate that ran;
- `Ignored`, with the reason `NO_CANDIDATE_ADMITTED`, when every candidate was refused. Its `rejections()` list the
  refusal of each one, in order, and its `explanation()` joins them. It writes nothing, though the guards of those
  candidates ran.

A choice never returns `Rejected`: its call requests no identifier. A failure throws what `execute` would throw, an
`UnhandledFailureException` for a domain failure that no route takes included, and a guard that throws fails the call,
so the candidates after it are not tried. A choice blocks on an asynchronous edge;
`shipments.async(() -> shipments.observe(shipment, report))` runs it on the machine's executor. A `StateMachine` built
with the DSL does the same with `machine.supplyAsync(() -> machine.choose(entity, candidates))`, since
`AsyncStateMachine` has no `choose`. It may start where a call may start: inside a call, from a guard, an action or an
observer for instance, it is refused before it starts with `ReentrantTransitionException`, as `execute` is.

In [ShipmentFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/shipment/ShipmentFlowTest.java),
a report of a delivery delivers the shipment:

```java
Shipment shipment = new Shipment("s1");

Outcome<Shipment, ShipmentStatus, ShipmentTransition> outcome =
        shipments.observe(shipment, new CarrierReport("delivered"));

assertThat(outcome).isApplied().requestedTransition(DELIVER).movedTo(DELIVERED)
        .hasEntityState(Shipment::status, DELIVERED);
```

`RETURN` was tried first, and its guard refused the report. The outcome of a choice that ran does not carry the
refusals of the candidates before the one it ran; the check reports them. A report that neither guard admits leaves
the shipment in transit:

```java
Shipment shipment = new Shipment("s2");

Outcome<Shipment, ShipmentStatus, ShipmentTransition> outcome =
        shipments.observe(shipment, new CarrierReport("delayed"));

RejectionsAssert<Shipment, ShipmentStatus, ShipmentTransition> rejections = assertThat(outcome).isIgnored()
        .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).hasEntityState(Shipment::status, IN_TRANSIT)
        .hasExplanation("no candidate was admitted: RETURN (GUARD returned): the carrier reported no return;"
                + " DELIVER (GUARD delivered): the carrier reported no delivery")
        .rejections().hasSize(2);
rejections.atIndex(0).isRejectedByGuardNamed(ShipmentGuardsRefs.Returned.NAME).transition(RETURN);
rejections.atIndex(1).isRejectedByGuardNamed(ShipmentGuardsRefs.Delivered.NAME).transition(DELIVER);
```

[Observers](commit-and-persistence.md#observers) see a choice as a call whose `MachineCall.request()` is
`CallRequest.CHOOSE`, with no identifier. Its `DEBUG` start line lists the candidates, as
`call=<id> start Shipment/ShipmentStatus choose RETURN,DELIVER`, and `call=<id> candidate DELIVER admitted` names the
one that ran; at `TRACE`, a `candidate ... refused` line gives each refusal, as [line formats](logs.md#line-formats)
lists.

## Check a choice

`canObserve` asks which candidate `observe` would run, without running it. It answers with a
`ChoiceEligibility<E, S, T>`:

- `Chosen` names the first candidate admitted, its `transition()`, and lists in `rejected()` the refusals of the
  candidates before it, empty when the first one is admitted;
- `NoneAdmitted` lists in `rejections()` the refusal of every candidate, and is never empty.

Both report `entity()` and `from()`, the state the check read: the chosen candidate's check for `Chosen`, the first
candidate's for `NoneAdmitted`. Neither names a destination. `chosen()` returns the candidate, or empty, and
`asChosen()` and `asNoneAdmitted()` return the answer as that variant, or empty. Each refusal is the
`Eligibility.Ineligible` that [an eligibility check](eligibility.md#read-the-answer) of that candidate answers, with
its kind, its reason and, for a guard, the guard's name.

The check runs the [eligibility check](eligibility.md) of each candidate in turn, with the same input, and stops at
the first that is `Eligible`. Each is a check of its own: it reads the state again, under a deadline of its own, and
logs its own `check=<id>` lines. Whatever it answers or throws, it writes no state and runs no action, hook, watch
reaction or automatic attempt, and it is no call, so no observer hears of it. A check that cannot answer throws its
`EligibilityCheckException`, and the candidates after it are not checked. An answer reserves nothing: the choice runs
every admission again. A choice check may run where a check may, as
[where a check may run](eligibility.md#where-a-check-may-run) says: unlike `observe`, it may be asked by a synchronous
guard, for an entity that no enclosing call or check holds, and each candidate's check is then nested in the guard's
call or check. A held entity is refused with `ReentrantTransitionException` before the first check starts, and such an
answer logs nothing.

In [ShipmentFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/shipment/ShipmentFlowTest.java),
the check of a delivery report chooses `DELIVER` once `RETURN` was refused, and moves nothing:

```java
Shipment shipment = new Shipment("s3");

assertThat(shipments.canObserve(shipment, new CarrierReport("delivered"))).chose(DELIVER).from(IN_TRANSIT)
        .rejections().single().isRejectedByGuardNamed(ShipmentGuardsRefs.Returned.NAME);
assertThat(shipment, Shipment::status).isIn(IN_TRANSIT);
```

## The rules the build checks

The processor refuses, as `SG516`:

- a `Choice` on a method that also carries a `Transition` or a `Check`;
- a method that returns anything but `Outcome<E, S, T>` or `ChoiceEligibility<E, S, T>`, whose parameters are not the
  entity and at most one input, or that declares varargs or method type parameters;
- a list of candidates that is empty or names a candidate twice;
- a candidate that no edge of the graph declares, or that is the exit of a sub-machine, which only the sub-machine
  runs;
- a candidate that an operation of the facade executes, when the input of the choice differs from that operation's:
  of another type, or present or absent where the operation's is not.

A `Choice` on a default, static or private method of the facade is `SG504`, as a `Transition` or a `Check` there is. A
candidate that failure routes use is accepted, and so is one with an operation of its own: a choice claims no
identifier, so it declares no edge and leaves `SG102` and `SG703` as they are. [Diagnostics](diagnostics.md) lists
these codes.

## After the commit

Once the candidate it ran committed, a choice runs the hooks and the watch reactions of its commit, as any call does,
then the [automatic chain](automatic-transitions.md#when-the-chain-runs) of each entity the commit moved, as an
operation does, and its outcome lists those attempts in `automaticSteps()`. A choice is no `advance`: after it, an
entity of a machine declared [`ON_ADVANCE`](automatic-transitions.md#chain-only-on-advance) runs no chain, the
entity of the choice included, until `advance(entity)` asks. A choice that admitted no candidate committed nothing, so
it runs no hook, no reaction and no chain.

## Test a choice

`GraphAssertions.assertThat(choiceEligibility)`, in `graphmaquina-test`, reads the answer of a check: `chose(id)` or
`admittedNone()`, and of both `from(state)` and `rejections()`, which reads the `rejected()` of a `Chosen` answer or
the `rejections()` of a `NoneAdmitted` one, each refusal with the view of an
[eligibility answer](eligibility.md#test-a-check). The outcome of a choice is read with `assertThat(outcome)`:
`isApplied()` or `isFailed()` with `requestedTransition(id)`, or `isIgnored()` with
`hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED)` and its `rejections()`. In
[ShipmentFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/shipment/ShipmentFlowTest.java),
a delivered shipment refuses both candidates with the reason of its policy, and its check admits none:

```java
Shipment shipment = new Shipment("s4");
shipments.observe(shipment, new CarrierReport("delivered"));

RejectionsAssert<Shipment, ShipmentStatus, ShipmentTransition> rejections =
        assertThat(shipments.observe(shipment, new CarrierReport("returned"))).isIgnored()
                .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).rejections().hasSize(2);
rejections.atIndex(0).hasNoTransition().hasReason(Shipments.DELIVERED_POLICY).transition(RETURN);
rejections.atIndex(1).hasNoTransition().hasReason(Shipments.DELIVERED_POLICY).transition(DELIVER);
assertThat(shipments.canObserve(shipment, new CarrierReport("returned"))).admittedNone().from(DELIVERED);
```

[What each entry point checks](testing.md#what-each-entry-point-checks) lists these views with the others.

---

Next: [Programmatic DSL](dsl.md). Back to the [documentation index](README.md).
