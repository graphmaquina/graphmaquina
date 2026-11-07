# Migration patterns for concepts without an equivalent

Five concepts of other state-machine libraries have no GraphMaquina feature and no example elsewhere in these pages:
entry and exit actions, history states, parallel regions, timers and deferred events. Each section below builds the
closest pattern from features GraphMaquina has. What a pattern changes for the developer, the warning to give when you
translate the concept, is in the concept's entry of [migrate a machine from another library](migrate-from-another-library.md).

[MigrationGuideFragmentsTest](../graphmaquina-test/src/test/java/consumer/MigrationGuideFragmentsTest.java) builds every
block of this page with the GraphMaquina Gradle plugin against the published artifacts, and runs JUnit tests of what
each section says the pattern does. The blocks leave out the package and the imports, and use the constants of their
enums through static imports. That test writes around them the entities, enums and facades each section describes,
with public accessors for the fields the section names.

## Contents

- [Entry and exit actions](#entry-and-exit-actions)
- [History states](#history-states)
- [Parallel regions](#parallel-regions)
- [Timers](#timers)
- [Deferred events](#deferred-events)

## Entry and exit actions

An entry action ran each time the machine entered its state, and an exit action each time it left it. GraphMaquina runs
code on edges, not on states. When the code may run after the move, put it in one after-commit hook, the machine's
default, that dispatches on the origin and the destination of each hop. A document machine whose `REVIEW` state
assigned reviewers on entry and released them on exit declares this hook as
`afterCommit = DocumentLifecycleRefs.OnHop.class` on its `Definition`:

```java
public final class DocumentLifecycle {
    private final ReviewBoard board;

    public DocumentLifecycle(ReviewBoard board) {
        this.board = Objects.requireNonNull(board);
    }

    @GraphHook
    public void onHop(Document document, CommitContext<DocumentState, DocumentTransition> commit) {
        if (commit.from() == commit.to()) {
            return; // a self-loop: decide whether the original machine ran exit and entry here
        }
        if (commit.from() == DocumentState.REVIEW) {
            board.release(document); // the original exit action of REVIEW
        }
        if (commit.to() == DocumentState.REVIEW) {
            board.assign(document); // the original entry action of REVIEW
        }
    }
}
```

`ReviewBoard` is the application's port, an interface with `assign` and `release`. Submitting a document runs
`assign`, and publishing it runs `release`. So does a publication that fails and takes its failure route back to
`DRAFT`, since a failure route commits a hop too. A request that a guard refuses, or that has no edge, commits nothing
and runs neither. `COMMENT`, a self-loop on `REVIEW`, reaches the hook with the same origin and destination, and the
hook skips it: decide there whether the original machine ran the exit and entry actions on a self-transition. An edge
that declares its own `afterCommit` does not run the default hook, so `WITHDRAW`, whose own hook does not call the
board, releases nobody: give such an edge a hook that calls the same code. When `assign` throws, the submission has
already committed `REVIEW`, and the caller receives `AfterCommitException`.

Code that must be able to stop the move, or must run before the commit, goes into the action of each edge that enters
or leaves the state instead, where a failure takes the edge's failure route.
[After-commit hooks](commit-and-persistence.md#after-commit-hooks) covers the hook itself.

## History states

A history state let a composite state resume at the step it was left in, instead of at its initial state. GraphMaquina
keeps no history: the entity's state field is the only memory, so these patterns store the step to resume in a field of
its own.

### Resume a flat machine

A wizard with two steps can pause and resume at the step it left. The hook of `PAUSE` stores that step in `resumeAt`, a
plain field of `Wizard` beside its state. `RESUME` leads to `RESUMING`, a real state whose two automatic edges return
the wizard to the stored step: `BACK_TO_ONE`, guarded, is tried first, and `BACK_TO_TWO`, without guards, comes last.
The enums are `WizardState { STEP_ONE, STEP_TWO, PAUSED, RESUMING, DONE }` and
`WizardTransition { NEXT, FINISH, PAUSE, RESUME, BACK_TO_ONE, BACK_TO_TWO }`. The hook and the guard:

```java
public final class WizardActions {
    @GraphHook
    public void rememberStep(Wizard wizard, CommitContext<WizardState, WizardTransition> commit) {
        wizard.setResumeAt(commit.from());
    }

    @GraphGuard
    public GuardResult pausedAtStepOne(GuardContext<Wizard, WizardState, WizardTransition> ctx) {
        return ctx.entity().getResumeAt() == WizardState.STEP_ONE
                ? GuardResult.pass()
                : GuardResult.reject("the wizard was paused at another step");
    }
}
```

The machine:

```java
@GenerateGraphApi
@WizardsGraph.Definition(
    initial = STEP_ONE,
    terminals = DONE,
    allowsCycle = PAUSED,
    state = WizardRefs.State.class,
    id = WizardRefs.Id.class)
@WizardsGraph.StatePolicy(state = STEP_ONE, otherwise = "go on to step two or pause")
@WizardsGraph.StatePolicy(state = STEP_TWO, otherwise = "finish or pause")
@WizardsGraph.StatePolicy(state = PAUSED, otherwise = "resume the wizard")
@WizardsGraph.StatePolicy(state = RESUMING, otherwise = "the wizard is returning to its step")
// the return to the stored step: one automatic attempt tries BACK_TO_ONE, then BACK_TO_TWO
@WizardsGraph.Transition(
    id = BACK_TO_ONE,
    from = RESUMING,
    to = STEP_ONE,
    automatic = true,
    guards = WizardActionsRefs.PausedAtStepOne.class)
@WizardsGraph.Transition(id = BACK_TO_TWO, from = RESUMING, to = STEP_TWO, automatic = true)
@WizardsGraph.AutomaticOrder(from = RESUMING, transitions = {BACK_TO_ONE, BACK_TO_TWO})
public interface Wizards extends Machine<Wizard, WizardState, WizardTransition> {

    @WizardsGraph.Transition(id = NEXT, from = STEP_ONE, to = STEP_TWO)
    Outcome<Wizard, WizardState, WizardTransition> next(Wizard wizard);

    @WizardsGraph.Transition(id = FINISH, from = STEP_TWO, to = DONE)
    Outcome<Wizard, WizardState, WizardTransition> finish(Wizard wizard);

    // the hook stores the step the wizard leaves
    @WizardsGraph.Transition(
        id = PAUSE,
        from = {STEP_ONE, STEP_TWO},
        to = PAUSED,
        afterCommit = WizardActionsRefs.RememberStep.class)
    Outcome<Wizard, WizardState, WizardTransition> pause(Wizard wizard);

    @WizardsGraph.Transition(id = RESUME, from = PAUSED, to = RESUMING)
    Outcome<Wizard, WizardState, WizardTransition> resume(Wizard wizard);
}
```

`resume(wizard)` returns the move into `RESUMING`. The wizard is back at its step when the call returns, and the
outcome's `automaticSteps()` holds the one attempt that took it there: for a wizard paused at step two, that attempt
tried `BACK_TO_ONE`, which its guard refused, and then took `BACK_TO_TWO`.
[Automatic transitions](automatic-transitions.md) explains attempts and their order.

### Restore a sub-machine

When the composite state is a [sub-machine](composition.md), entering its state always restarts the child at its
initial state. The edge that enters can put the child back: the child restarts before that edge's action runs, so the
action finds it at its initial state and cascades it to the stored step, through an edge of the child from its initial
state to each step it may return to.

A loan application runs its checks as a sub-machine over the same entity while it is in `CHECKS`: `IDENTITY`, then
`INCOME`, then the terminal `DONE`, whose exit approves the application. `HOLD` leaves `CHECKS` for `ON_HOLD`, and
`RESUME` enters `CHECKS` again. Besides `VERIFY_IDENTITY`, the checks declare `RESTORE_INCOME`, from `IDENTITY` to
`INCOME`, and the application keeps the step to restore in `resumeCheckAt`. `HOLD` names `rememberCheck` as its
`afterCommit`, and `RESUME` names `restoreCheck` as its action:

```java
public final class CheckHistory {
    private CheckHistory() { }

    // the hook of HOLD, which leaves CHECKS: store the step the checks reached
    @GraphHook
    public static void rememberCheck(Application application) {
        application.setResumeCheckAt(application.getCheck());
    }

    // the action of RESUME, which enters CHECKS: the checks restarted at IDENTITY, so cascade them back
    @GraphAction
    public static ActionOutcome restoreCheck(
            TransitionContext<Application, ApplicationStatus, ApplicationTransition> ctx) {
        if (ctx.entity().getResumeCheckAt() == CheckStatus.INCOME) {
            ctx.cascade(Checks.class, ctx.entity(), CheckTransition.RESTORE_INCOME);
        }
        return ActionOutcome.ok();
    }
}
```

Resuming an application held at `INCOME` restarts its checks at `IDENTITY` and cascades `RESTORE_INCOME` in the same
commit: the outcome's `starts()` hold the restart, from `INCOME` to `IDENTITY`, and its `records()` hold `RESUME` and,
one level deeper, `RESTORE_INCOME`. `RESTORE_INCOME` runs its guards like any edge, and a refusal aborts the whole
resume, which then writes nothing. With one step to restore, one `if` is enough; with several, the action takes one
branch per step, each with its own edge from the initial state.

## Parallel regions

Orthogonal regions put one machine in a state of each region at once. GraphMaquina has no regions: each region becomes
a machine of its own over the same entity, with its own state field and its own state enum, and one `@StateGraphs` set
holds them. An order is paid in one region, `PaymentStatus { UNPAID, PAID }` in its `payment` field, and packed and
shipped in another, `PackingStatus { PACKING, PACKED, SHIPPED }` in its `packing` field. It ships once both regions are
done, so `SHIP` is the join, and whichever region arrives last has to request it:

```java
@GenerateGraphApi
@OrderPaymentsGraph.Definition(
    initial = UNPAID,
    terminals = PAID,
    state = OrderRefs.Payment.class,
    id = OrderRefs.Id.class)
@OrderPaymentsGraph.StatePolicy(state = UNPAID, otherwise = "pay the order")
public interface OrderPayments extends Machine<Order, PaymentStatus, PaymentTransition> {

    @OrderPaymentsGraph.Transition(id = PAY, from = UNPAID, to = PAID, action = RegionRulesRefs.ShipIfPacked.class)
    Outcome<Order, PaymentStatus, PaymentTransition> pay(Order order);
}

@GenerateGraphApi
@OrderPackingGraph.Definition(
    initial = PACKING,
    terminals = SHIPPED,
    state = OrderRefs.Packing.class,
    id = OrderRefs.Id.class)
@OrderPackingGraph.StatePolicy(state = PACKING, otherwise = "pack the order")
@OrderPackingGraph.StatePolicy(state = PACKED, otherwise = "the order ships once it is paid")
// the join, tried after an ordinary hop into PACKED, or on advance
@OrderPackingGraph.Transition(
    id = SHIP,
    from = PACKED,
    to = SHIPPED,
    automatic = true,
    guards = RegionRulesRefs.Paid.class)
public interface OrderPacking extends Machine<Order, PackingStatus, PackingTransition> {

    @OrderPackingGraph.Transition(id = PACK, from = PACKING, to = PACKED)
    Outcome<Order, PackingStatus, PackingTransition> pack(Order order);
}
```

`SHIP` is automatic and guarded by the payment region's state, so packing an order that is already paid ships it
before `pack` returns, in an automatic attempt of its own after the commit of `PACKED`. When the payment arrives last,
a commit that moved only the payment would run no chain of the packing region, since a commit runs the chains of the
machines it moved, so the action of `PAY` requests `SHIP` itself, as a cascade in the same commit. The action and the
guard read the other region with `ctx.peer(...)`, which sees the pending state of a step earlier in the same call:

```java
public final class RegionRules {
    private RegionRules() { }

    // the payment arrives: if the packing region already waits in PACKED, join now
    @GraphAction
    public static ActionOutcome shipIfPacked(TransitionContext<Order, PaymentStatus, PaymentTransition> ctx) {
        if (ctx.peer(OrderPacking.class).stateOf(ctx.entity()) == PackingStatus.PACKED) {
            ctx.cascade(OrderPacking.class, ctx.entity(), PackingTransition.SHIP);
        }
        return ActionOutcome.ok();
    }

    @GraphGuard
    public static GuardResult paid(GuardContext<Order, PackingStatus, PackingTransition> ctx) {
        return ctx.peer(OrderPayments.class).stateOf(ctx.entity()) == PaymentStatus.PAID
                ? GuardResult.pass()
                : GuardResult.reject("the order is not paid");
    }
}

@StateGraphs
public interface OrderRegions extends Machines {
    OrderPayments payments();

    OrderPacking packing();
}
```

Packing an unpaid order moves it to `PACKED`, and the outcome reports the refused attempt of `SHIP` in
`automaticBlock()`. Paying it then commits `PAID` and `SHIPPED` in one call, whose `records()` hold `PAY` and, one level
deeper, `SHIP`. The action cascades only when the packing region waits in `PACKED`, because a cascade that the other
machine refuses aborts the whole call. Instead of the cascade, the application can call `advance(order)` on the packing
machine once the payment is done. A fork, an edge that enters several regions at once, becomes the same kind of action:
one cascade into each other region. [Read peers and cascade transitions](coordination.md#read-peers-and-cascade-transitions)
covers peer views and cascades.

## Timers

A timer moved the machine after a delay, such as a reservation that expires fifteen minutes after it is held.
GraphMaquina has no timer, and nothing in it fires on its own. A scheduler of your own makes the call when the time
comes, and a guard compares a deadline stored on the entity with a clock, so a call that comes too early is refused.
The guard's host receives the clock:

```java
public final class ReservationGuards {
    private final InstantSource clock;

    public ReservationGuards(InstantSource clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @GraphGuard
    public GuardResult expired(GuardContext<Reservation, ReservationState, ReservationTransition> ctx) {
        return clock.instant().isBefore(ctx.entity().getExpiresAt())
                ? GuardResult.reject("the reservation has not expired yet")
                : GuardResult.pass();
    }
}
```

On the facade, the operation the scheduler calls:

```java
@ReservationsGraph.Transition(
    id = EXPIRE,
    from = HELD,
    to = EXPIRED,
    guards = ReservationGuardsRefs.Expired.class)
Outcome<Reservation, ReservationState, ReservationTransition> expire(Reservation reservation);
```

In the application, here with a `ScheduledExecutorService`; a timer that must survive a restart needs durable
scheduling of your own instead:

```java
ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
Runnable expiry = () -> reservations.expire(reservation);
timers.schedule(expiry, 15, TimeUnit.MINUTES);
```

Before the deadline, the guard refuses `expire` with its reason. Once the deadline has passed, the reservation stays in
`HELD` until the call, which then moves it to `EXPIRED`. A call that comes after the reservation was confirmed finds no
edge and is refused as `NO_TRANSITION`, and one that comes after an `EXTEND` self-loop whose action moved the deadline
later is refused by the guard. To use an automatic edge instead, mark `EXPIRE` `automatic = true` and have the
scheduler call `reservations.advance(reservation)`. Every arrival in `HELD` by an ordinary hop before the deadline, such
as an `EXTEND`, then runs an attempt that the guard refuses, and the outcome of that arrival reports it in
`automaticBlock()`; an arrival by a failure route that does not continue, the default, or by a call whose hook threw
runs none. The `asyncTimeout` and `@Timeout` settings are no timers: they bound how long a call waits for
asynchronous work, as [deadline rules](async-deadlines.md#deadline-rules) explains.

## Deferred events

A deferred event waited in a queue until the machine reached a state that accepts it. GraphMaquina never queues a
request: the current state accepts it or refuses it at once. Record the deferred request on the entity instead, as a
plain field, and give the state where it becomes acceptable an automatic edge guarded by that field. An order cannot
be cancelled while it is `PROCESSING`, so it keeps the cancellation for `READY`. Its states are `NEW`, `PROCESSING`,
`READY`, `SHIPPED` and `CANCELLED`; `cancel` is accepted in `NEW` and `READY`, and `CANCELLED` is final. On the facade:

```java
// the deferred cancellation, tried after an ordinary hop into READY, or on advance
@OrdersGraph.Transition(
    id = CANCEL_WHEN_READY,
    from = READY,
    to = CANCELLED,
    automatic = true,
    guards = OrderRulesRefs.CancellationRequested.class)
```

The guard reads the field:

```java
public final class OrderRules {
    private OrderRules() { }

    @GraphGuard
    public static GuardResult cancellationRequested(GuardContext<Order, OrderStatus, OrderTransition> ctx) {
        return ctx.entity().isCancellationRequested()
                ? GuardResult.pass()
                : GuardResult.reject("no cancellation is pending");
    }
}
```

In the application, a cancellation that the current state refuses is recorded:

```java
Outcome<Order, OrderStatus, OrderTransition> outcome = orders.cancel(order);
if (outcome.code() == OutcomeCode.NO_TRANSITION) {
    order.requestCancellation(); // PROCESSING refused it: keep it for READY
}
```

When `finish(order)` moves the order to `READY`, the order's chain takes `CANCEL_WHEN_READY` in an attempt of its own,
listed in the outcome's `automaticSteps()`. An order that arrives in `READY` by an ordinary hop with no request
recorded runs the same attempt, which the guard refuses, and the outcome reports it in `automaticBlock()`: code that
treats a block as an error must not do so for this edge. A request recorded while the order is already in `READY`
waits until the application calls `advance(order)`, whose outcome is that attempt's own. So does a request already
recorded when the order arrives by a hop that runs no chain: a failure route that does not continue, which is the
default, or a call whose hook threw. Since `CANCELLED` is final, the flag needs no clearing; for a target that is not
final, clear it in the deferred edge's action or hook, or the edge is taken again on the next arrival.

---

Next: [Building with the typed graph API](typed-annotations-build.md). Back to the [documentation index](README.md).
