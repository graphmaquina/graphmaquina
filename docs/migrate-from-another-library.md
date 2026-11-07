# Migrate a machine from another library

This page translates a state machine written with another library, or by hand, into GraphMaquina, one general concept
at a time. It names no library: find each concept your machine uses under its general name. Every entry says what
GraphMaquina offers for the concept and links the page that documents it. When the behavior differs, its "What
changes" is the warning to give the developer. The five concepts that need code GraphMaquina does not document
elsewhere get a built and tested pattern on [migration patterns](migration-patterns.md).

## Contents

- [The model: state in the entity](#the-model-state-in-the-entity)
- [The procedure](#the-procedure)
- [Concepts with an equivalent](#concepts-with-an-equivalent)
- [Concepts that behave differently](#concepts-that-behave-differently)
- [Concepts without an equivalent](#concepts-without-an-equivalent)
- [Concepts that depend on the original machine](#concepts-that-depend-on-the-original-machine)

## The model: state in the entity

Where a library keeps the current state in a machine object, one per entity, GraphMaquina keeps it in the entity, and
several entries below follow from that:

- The state is a field of your entity. Each call reads it through the getter, and the commit writes it through the
  setter, as [Ticket.java](quickstart.md#ticketjava) shows.
- One machine serves every entity of its type: each operation of the generated facade takes the entity as its first
  argument.
- The state starts initialized. `initial` describes the graph and initializes no entity, and a null state aborts the
  call, as [initialize and protect state](commit-and-persistence.md#initialize-and-protect-state) says.
- The runtime does not protect an entity against concurrent transitions: serialize the calls on one entity yourself.

## The procedure

1. Inventory the original machine: its states, and which are initial and final; its events, with their payloads; every
   transition as source, event, guard, target and action; and which concepts of this page it uses. Note also where its
   state lives and how its machine instances are created, started and stored.
2. Translate it concept by concept. Write the two enums, move the state into a field of the entity, and declare the
   facade with one operation per event, one `Transition` per edge, a policy for every state that is not final,
   `terminals` and `allowsCycle`, then the `@StateGraphs` set. Translate each further concept with its entry. Keep the
   original machine until the developer decides to remove it.
3. Warn. For every concept the original uses, whatever its kind, tell the developer each sentence of its "What changes"
   that applies, naming the concept, and each note that changes what callers observe. End with the full list.
4. Test parity with `graphmaquina-test`, [installed](testing.md#install) as a test dependency, building the set the way
   the application does:
   - Structure, from `machines.x().definition()`: `hasInitial`, `hasTerminalsExactly`, and for each original state
     `hasTransitionsExactly(state, ids...)` with the events it accepted; `transition(from, id).hasTarget(to)` for each
     edge; `policy(state).rejects(id, reason)` for the events it refused. `GraphSnapshots.assertMatches` can pin the
     whole graph, as [goldens and structural snapshots](testing.md#goldens-and-structural-snapshots) shows.
   - Behavior: replay each path of the original through the facade with real entities, and assert each outcome with
     `isApplied().movedTo(state)`, `isRejected().hasNoTransition()`, `isRejectedByGuardNamed(name)` or
     `isFailed().hasFailureTransition(id).movedTo(state)`, and the live state with `hasEntityState(getter, state)`. A
     [call recorder](testing-samples.md#the-call-recorder) checks a sequence, and a
     [state snapshot](testing-samples.md#tracked-entity-state) checks that nothing changed where the original ignored
     an event or rolled back. Where a pattern adds automatic steps, assert `automaticSteps()` and the entity's final
     state, and `automaticBlock()` on the arrivals where no automatic edge should be taken.
   - A topology path proves nothing about a run, and `isApplied()` does not prove that automatic steps and reactions
     succeeded, as [what a check does not prove](testing.md#what-a-check-does-not-prove) says.

## Concepts with an equivalent

GraphMaquina behaves the same for the ordinary use of these concepts; the notes say what to write differently.

### States, events and transitions

Two handwritten enums, one of states and one of transition identifiers, which are the events; a facade interface that
extends `Machine<E, S, T>` and is marked `@GenerateGraphApi`; one generated `Transition(id, from, to)` per edge, on the
operation that requests it, or on the interface for an edge without one. An event accepted in several states is one
identifier with several `from` states. See [understand the graph](graph-model.md#understand-the-graph) and the
[quickstart](quickstart.md).

Notes: each event becomes one facade method, and the facade has no generic `fire(event)`. To dispatch an event value at
runtime, map it to the method with a `switch`, or build the machine with the [DSL](dsl.md#build-machines-and-a-registry)
and call `execute(entity, id)`. An event that only the machine raises, such as a failure route or the target of a
cascade or a watch, is a `Transition` on the interface with no method. Every cycle passes through a state listed in
`allowsCycle` (`SG204`), and two methods cannot claim one identifier (`SG703`).

### Several transitions between the same pair of states

Parallel edges, each with its own identifier, guards and action. An edge is keyed by its origin and its identifier,
not by its destination. See [identity, not destination](graph-model.md#identity-not-destination).

Notes: a request never falls back to another edge that reaches the same state, so test each edge by its identifier.

### Self-transitions

A self-loop: an ordinary edge that ends where it starts. Its state is listed in `allowsCycle`, and a final state has
none. A request for it returns `Applied`, never `Ignored`. See
[absence, self-loops and reevaluation](graph-model.md#absence-self-loops-and-reevaluation).

### Asking which events are permitted now

One check per identifier: a facade method that carries the generated `Check(id = ...)` and returns
`Eligibility<E, S, T>`, or `StateMachine.eligibility(entity, id)`. A check runs the admission and the guards, not the
action. No single call lists the permitted events with their guards evaluated: loop over `transitionsFrom(state)`, the
identifiers declared from the entity's state, and check each. See [check an operation before you call it](eligibility.md).

Notes: a facade checks only the operations it declares a `Check` for, while a `StateMachine` checks any identifier. An
`Eligible` answer reserves nothing and says nothing about the action's result, and observers do not see a check.

## Concepts that behave differently

A GraphMaquina feature covers each of these concepts, with differences the developer can observe at runtime.

### Where the state lives

A field of the entity marked `@GraphProperty`, with a public reader and a public writer, named by the `state` of the
`Definition`; with the DSL, `StateMachines.from(definition).state(getter, setter)`. This is direct when the original
already kept the state in a field of the domain object. See [properties and accessors](graph-model.md#properties-and-accessors).

What changes: the state no longer lives in a machine object. It is a field of your entity, which you initialize when
you create the entity and persist with your normal mapping. The runtime reads that field at the start of each call and
writes it through the setter at commit; there is no per-entity machine to ask for its current state, so read the
entity's getter. A null state aborts the call instead of starting at the initial state. Action code must not call the
state setter: the commit detects the change and fails with `CommitFailedException`.

### Initial and final states

`initial` and `terminals` on the `Definition`; with the DSL, `initial(state)` and `terminal(state)`. A hook reads
`CommitContext.isTerminal()` for the destination of its hop. See [structural rules](graph-model.md#structural-rules).

What changes: `initial` describes the graph; it does not put new entities in that state, and nothing starts a machine:
set the field when you create the entity. A new entity runs no hook and no automatic edge of its initial state until a
call moves it or `advance(entity)` asks. A final state accepts no edge at all, not even a self-loop, and every request
there is refused as `NO_TRANSITION`, with the state's policy reason, or `terminal state <NAME> accepts no transitions`
when it declares none. There is no "machine completed" event: react to a final destination in an after-commit hook
through `CommitContext.isTerminal()`. The build also requires every state to be reachable from `initial` and every
state that is not final to have an outgoing edge.

### Internal transitions

A self-loop, with or without an action. The "internal edges" of
[operations and internal edges](graph-model.md#operations-and-internal-edges) are another thing: edges without an
operation.

What changes: GraphMaquina has no internal transition. A self-loop is a full hop: the commit calls the state setter
with the same state, the edge's hook or the machine's default hook runs, the hop appears in `records()`, and the
entity's automatic chain runs again. If the state holds a sub-machine, the self-loop restarts the child at its initial
state. Watches do not react to it, because the entity's final state equals its original state. The state must be
listed in `allowsCycle`.

### Events with data

An operation with a second parameter, `Outcome<E, S, T> op(E entity, I input)`; guards and actions read it with
`ctx.input(I.class)`, an `Optional<I>`. With the DSL, `execute(entity, id, input)`. An operation takes one input at
most, so put several values in an immutable record, and an operation that takes one cannot be the identifier of an
automatic edge (`SG515`). See [operations and internal edges](graph-model.md#operations-and-internal-edges) and
[actions, guards and failure routes](actions-and-failures.md).

What changes: only the edge's guards, its action and its failure route's action see the event's data. Cascaded steps,
sub-machine exits, watch reactions and automatic steps receive no input, and hooks, observers and transition records
never see it: code of the original that read the event's data in a listener, an entry or exit action or another
machine must get it another way. For the cascades, exits and hooks of the same call, the action copies it into the
call's flow; watch reactions and automatic steps are calls of their own with their own flow, so store on the entity
what they need. A null input is refused with `NullPointerException` before the call starts.

### Guards

A `@GraphGuard` method that returns `GuardResult.pass()` or `GuardResult.reject(reason)`, named in the edge's `guards`;
several run in order, and all must pass. With the DSL, `guard(name, guard)`. See
[guards and the reason a request is refused](graph-model.md#guards-and-the-reason-a-request-is-refused).

What changes: only one edge exists per state and event, so "the same event, several guarded targets, the first passing
guard wins" is refused at build time (`SG102`); [choice and junction](#choice-and-junction-pseudo-states) gives the
replacement. A guard that refuses produces a `Rejected` outcome that carries the guard's name and its reason, not a
silent no-op. A guard that throws, or returns null, aborts the call with `TransitionAbortedException` instead of
counting as false. Guards must be free of side effects: the guards of automatic candidates that end up refused still
ran.

### Transition actions

A `@GraphAction` method that returns `ActionOutcome.ok()` or `ActionOutcome.failed(...)`, named in the edge's `action`,
one per edge at most; an edge without an action is a move. With the DSL, `action(name, action)`. See
[actions, guards and failure routes](actions-and-failures.md).

What changes: one action per edge, so several actions of one transition become one method. The action must return an
outcome: `failed(...)` is a domain failure that needs a [failure route](#errors-in-actions), or the call throws
`UnhandledFailureException` and commits nothing. The action runs before the state is written, so the entity still shows
the origin state while it runs. Changes the action makes to other fields of the entity, or outside the machine, are
not undone when the call aborts.

### Composite states

A sub-machine: the parent declares `@GraphPeers(Child.class)` and one generated `Submachine(state, ..., exits)` per
bound state; with the DSL, `submachine(state, childDefinition, ...)` and `exit(terminal, id)`. Parent and child belong
to the same `@StateGraphs` set. See [compose machines](composition.md).

What changes: the inner states become a separate machine with its own state enum and field, on a related entity or on
the same one. Events are not delegated: an event for the inner states is a call on the child's facade, an event for
the outer state a call on the parent's, and an event the child does not handle is refused by the child, not passed to
the parent. Entering the bound state starts the child at its initial state in the same commit, every time, also on a
self-loop. The parent leaves the state when the child reaches one of its final states, through the exit you map for
that final state, and every final state of the child needs one. A parent edge out of the bound state interrupts the
child and leaves it where it was unless its action cascades into it; afterwards, calls to the child are refused as
`DETACHED`. The exit identifiers cannot be requested from the bound state by any other path (`RESERVED_EXIT`). A bound
state holds one child at most and has no automatic edge. The child's state field must already hold a state when the
parent enters the bound state: the start reads it before it restarts the child, and a null aborts the parent's call.

### Extended state variables

Fields of the entity for values that outlive a call, and the call's flow, `ctx.flow()` with typed `FlowKey`s, for
values the steps and hooks of one call share. See [the call's flow](call-flow.md).

What changes: there is no variable map owned by the machine. Values that must survive between events are fields of
your entity, persisted with it. The flow lives for one call only and is never persisted; the next call starts with an
empty one. A field an action sets stays set even when the call fails; to set it only when the call commits, leave the
value in the flow and assign it from an after-commit hook.

### Transitions that fire on their own

Edges marked `automatic = true`, or `automatic()` with the DSL, tried in the order that `AutomaticOrder` declares for
their state; `advance(entity)` makes one attempt on request. See [automatic transitions](automatic-transitions.md).

What changes: an automatic edge is tried only after a call commits a move of the entity, or when you call
`advance(entity)`. A change of data without a transition triggers nothing, and a new entity in its initial state runs
nothing until then. Each attempt is a separate call after the commit: its failure never reaches the caller of the
original operation, which reads it in `automaticSteps()`, `automaticFailure()`, `automaticAbort()` or
`automaticBlock()`. When every automatic edge of a state is guarded, each arrival in that state by an ordinary hop, a
self-loop included, while no guard passes, runs an attempt that ends without a move and is reported in
`automaticBlock()`. An arrival by a failure route that does not continue, the default, or by a call whose hook threw
runs no attempt. A state with two or more automatic edges needs an explicit order, automatic edges may not form a
cycle, and an attempt past `maxAutomaticChain`, 10 by default, that admits an edge aborts before its action runs.

### Listeners and interceptors

A `MachineObserver` (`onStarted`, `onCompleted`, `onFailed`) registered with `observer(...)` on the set's builder sees
every call; `@GraphHook` after-commit hooks run per edge or as the `Definition`'s default; a guard vetoes a move; the
runtime's `System.Logger` traces. See [observers](commit-and-persistence.md#observers) and [logs](logs.md).

What changes: observers watch and cannot change or veto a call: an exception they throw is logged as a warning and
ignored, and a call they start on their own thread is refused with `ReentrantTransitionException`. They see every
call, refused ones included, through `onCompleted`, and technical failures through `onFailed`. Observers belong to a
set or registry; a machine built standalone has none. There is no global "before transition" interceptor: a veto is a
guard on each edge. A hook runs after the state is written, and its exception becomes `AfterCommitException` with the
state already committed.

### Persistence and restoration

The state field is persisted with the entity by the application's own mapping, and restoring is loading the entity:
the next call reads the field. See [framework and database boundaries](commit-and-persistence.md#framework-and-database-boundaries).

What changes: there is no machine context to persist or restore, and no persister. GraphMaquina's commit is the write
of the state through the setter, in memory; it does not commit your database transaction, which can still roll back
afterwards, and an after-commit hook does not mean the database committed. GraphMaquina provides no locking or
versioning: serialize conflicting calls or use your database's locking. If your code throws because an automatic step
failed, a transaction that rolls back on that exception undoes progress the outcome reports as committed.

### Errors in actions

A failure route: `onFailure` names another edge from the same origin, taken when the action returns
`ActionOutcome.failed(...)` or throws a type listed in `failsOn`; `recoveries` send failures of a `FailureKind` or an
exception type to different edges. With the DSL, `onFailure`, `failsOn` and `recover`. See
[domain failures](actions-and-failures.md#domain-failures).

What changes: an exception the edge does not declare aborts the call: nothing is committed, and the caller receives
`TransitionAbortedException` with your exception as its cause, not your exception itself. A declared failure commits
the failure route instead of the planned move, and the caller receives `Outcome.Failed` (`FAILURE_ROUTED`), not an
exception. A returned failure with no route throws `UnhandledFailureException`. Guards are skipped on the failure
route, and failure routes do not chain. Nothing retries, and nothing undoes side effects the action already had.

### Several machines that collaborate

A `@StateGraphs` set holds the machines. In guards and actions, `ctx.peer(Facade.class)` reads another machine, and
`ctx.cascade(...)` and `ctx.cascadeAll(...)` move it in the same unit of work; a watch reevaluates an owner after its
peers commit. See [machine sets, cascades and watches](coordination.md), and for machines of another module or jar,
[compose machines across modules and JARs](cross-module-composition.md).

What changes: events are not broadcast between machines. An action does not call another facade on its own thread,
which is refused with `ReentrantTransitionException`; it cascades, and a cascade shares the caller's commit: if the peer
refuses, or fails without a route, the whole call aborts and nothing is written. To react after another machine has
committed, use a watch: it runs as a separate call after the commit, and its failure does not undo the commit that
triggered it.

## Concepts without an equivalent

No GraphMaquina feature exists for these concepts. Each entry gives the closest pattern, built from other features.

### Entry and exit actions

Closest pattern: one after-commit hook, the `Definition`'s default, that runs the exit action of `commit.from()` and
the entry action of `commit.to()`; or, for code that must run before the commit or be able to fail the move, the action
of each edge that enters or leaves the state. See [the pattern](migration-patterns.md#entry-and-exit-actions).

What changes: as a hook, the code runs after the state is written: it cannot veto the move, and an exception there
becomes `AfterCommitException` with the state already committed. It runs once per committed hop, failure routes and
self-loops included, and never for a rejected or aborted request. Nothing runs when an entity is created in its initial
state, nor when a sub-machine starts, because a start is not a hop. In a cascade, every hop's hook runs after the final
write, so `commit.to()` can differ from the entity's current state. An edge that declares its own hook replaces the
default one, so that hook must call the same code; the DSL has no default hook, so declare it on every edge. A parent
that leaves a state holding a sub-machine runs nothing in the child. The hook does not see the event's data: an entry
action that reads it needs an action on every edge that enters the state, copying the input into the call's flow for
the hook to read, and an edge without an action must be given one. As actions, the code is repeated on each edge and
runs before the commit, so a failure there takes the edge's failure route.

### Choice and junction pseudo-states

Closest pattern: a real intermediate state whose outgoing edges are automatic, each guarded, tried in the order of
`AutomaticOrder`, with an edge without guards last as the "else" branch, as the onboarding's `ADDRESS_SET` does in
[declare automatic edges](automatic-transitions.md#declare-automatic-edges). Or one identifier per branch, when the
caller can decide, with an [eligibility check](eligibility.md) to tell it which one is admitted.

What changes: GraphMaquina has no pseudo-states, and one event cannot have several guarded targets from one state
(`SG102`). With an intermediate state, the decision point becomes a real state: the entity is committed there, the
setter runs and hooks and watches react, and then one automatic attempt, a call of its own, tries the branches in
order and runs the first one admitted. The caller's outcome reports the move into the intermediate state; the final
state is in `automaticSteps()` and on the entity. If no branch is admitted, the entity stays in the intermediate state
and the outcome reports the attempt in `automaticBlock()`, whose `rejections()` list each refused branch; a failing
branch is reported in `automaticFailure()` or `automaticAbort()`, never thrown. Since the attempt is a call of its own,
the branches' guards get an empty input and a flow of their own: they decide from the entity's fields, not from the
event's data or from values the original call put in its flow. An operation that takes an input cannot be a branch
(`SG515`), the intermediate state needs a policy and cannot hold a sub-machine (`SG807`), and the branch without guards
comes last (`SG109`).

### History states

Closest pattern: in a flat graph, a hook of the leaving edge stores the state the entity leaves in a plain field, and
the return goes through an intermediate state whose automatic edges, guarded by that field, lead back to it. When the
inner states are a sub-machine, the edge that enters the bound state cascades the restarted child back to the stored
step. Or the inner states stay an independent machine over the same entity, not bound as a sub-machine, so nothing
resets them. See [the patterns](migration-patterns.md#history-states).

What changes: GraphMaquina has no history states. A sub-machine restarts at its initial state each time its state is
entered, whatever state it had reached. With the resume pattern, the stored step is an ordinary field you persist, the
return goes through a real intermediate state, as for [choice and junction](#choice-and-junction-pseudo-states), and
the outcome of the resume call reports that intermediate state; the restored state is on the entity and in
`automaticSteps()`. With the restoring cascade, the restart and the restore happen in the same commit, the child needs
an extra edge from its initial state to each state it may return to, those edges run their guards, actions and hooks
like any edge, and a refused cascade aborts the parent's call. With an unbound machine, you lose the automatic start,
the exits and the `DETACHED` protection of a sub-machine, and it never resets either: if the original also entered
the composite state without history, that reset needs an explicit edge of the inner machine.

### Parallel regions

Closest pattern: one machine per region, each over the same entity with its own state field and state enum, held by
one `@StateGraphs` set. A region's guard reads another region with `ctx.peer(Other.class).stateOf(ctx.entity())`, and
an action moves another region in the same commit with `ctx.cascade(Other.class, ctx.entity(), id)`. See
[the pattern](migration-patterns.md#parallel-regions).

What changes: GraphMaquina has no regions and no fork or join. Each region is a machine of its own, so an event goes to
one region's facade, never to all regions at once, and the combined state is the set of fields. A fork becomes an
action that cascades into the other regions, in the same commit. A join becomes a guard on the joining region's edge
that reads the other regions' states, and something must request that edge when the last region arrives: the action
of the region that arrives last cascades into the joining region, or your code calls the operation, or `advance` for
an automatic edge, on the joining region. An automatic edge of the joining region is not tried when only another
region moved, because a commit runs the automatic chains of the machines it moved, not of the others; when the join is
an automatic edge, each arrival of the joining region before the others are done reports the refused attempt in
`automaticBlock()`. A refusal inside a cascade aborts the whole call. A state can hold one sub-machine only, so regions
cannot all be sub-machines of one state. Two machines may share an entity only with different state enums.

### Timers and timeouts

Closest pattern: a scheduler of the application, such as a `ScheduledExecutorService`, a job runner or a database
poll, calls an operation such as `expire(entity)` when the time comes, or `advance(entity)` for an automatic edge; the
edge's guard compares a deadline stored on the entity with a clock its host receives. See
[the pattern](migration-patterns.md#timers).

What changes: GraphMaquina has no timers and nothing fires on its own: watches are not polled, and automatic edges run
only after a commit or on `advance`. Your scheduler makes the call, so a timer that must survive a restart needs
durable scheduling of your own. If the entity has moved meanwhile, the call is refused (`NO_TRANSITION`) or the guard
refuses it, but only while the identifier and the guard tell the cases apart: when the same identifier is declared
from another state the entity is now in, a stale timer applies that edge if its guard passes there. With the
automatic-edge variant, every arrival in the timed state by an ordinary hop before the deadline, a self-loop included,
runs an attempt that the guard refuses: the outcome reports it in `automaticBlock()`, `automaticSteps()` lists it, and
observers see an extra call. Calls from the scheduler are not serialized with other calls on the same entity. The
`asyncTimeout` and `@Timeout` settings are not state timers: they bound how long a call waits for an asynchronous action
or guard, and when they expire the call aborts and takes no failure route.

### Deferred events

Closest pattern: record the deferred request as data on the entity, in a plain field, and declare an automatic edge
from the state where the event becomes acceptable, guarded by that field. When a call moves the entity into that
state, its automatic chain takes the edge; if the entity is already there when the request is recorded, call
`advance(entity)`. See [the pattern](migration-patterns.md#deferred-events).

What changes: GraphMaquina never queues an event. A request the current state does not accept returns `Rejected` with
`NO_TRANSITION` at once, and it is your code that records it for later. The recorded request is a field you persist; it
carries no payload unless you store one on the entity, because an automatic edge receives no input. After a commit
that moves the entity into the state by an ordinary hop, the deferred edge runs as a separate call whose result is in
`automaticSteps()`; when you call `advance`, the attempt is that call, and its result is that call's own outcome. An
arrival by a failure route that does not continue, the default, or by a call whose hook threw runs no attempt, so the
request waits until you call `advance`. Every other arrival in that state by an ordinary hop, a self-loop included,
with no request recorded, also runs an attempt that the guard refuses: the outcome reports it in `automaticBlock()`,
`automaticSteps()` lists it, observers see an extra call, and code that treats `automaticBlock()` as an error, as the
controller of [what the caller sees](automatic-transitions.md#what-the-caller-sees) does, must not do so for this edge.
If that state has other automatic edges, declare their order with `AutomaticOrder` (`SG108`). When the deferred edge
leads to a state that is not final, clear the field in its action or hook, or the edge is taken again the next time
the entity arrives.

### One machine object per entity

Closest pattern: one facade per machine type, obtained once from the generated set and shared; each call names the
entity. See [App.java](quickstart.md#appjava).

What changes: there is no machine instance per entity to create, cache, look up or dispose of. Build the set once for
the application's lifetime and pass the entity to each operation. Anything the original stored per machine instance,
such as variables, listeners or history, moves to the entity's fields or to the set.

### Starting, stopping and resetting a machine

Closest pattern: to start, create the entity with its initial state; to stop, move it to a final state, which refuses
everything; to reset or restore, set the entity's field outside any call, as when loading it. The set's `close()` only
closes the executors it owns. See [build and manage machine sets](coordination.md#build-and-manage-machine-sets).

What changes: nothing starts or stops per entity. Creating an entity runs no hook and no automatic edge; call
`advance(entity)` if its initial state has automatic edges to run. Closing the set closes its default executors and
waits for asynchronous work; it is not a barrier for synchronous calls. Outside a call your code may set the state
field, as when loading an entity; during a call only the runtime writes it.

### Event queues and concurrent events

Closest pattern: the caller serializes the calls on one entity. An event that an action raised on its own machine
becomes a call from an after-commit hook through the synchronous facade, an automatic edge, or, for another machine,
a cascade; `machine.async(...)` runs a whole call on an executor. See [asynchronous work](async.md).

What changes: GraphMaquina has no event queue. Each call runs to its end, its hooks, watch reactions and automatic
steps included, before it returns its outcome. Calls on the same entity from several threads are not serialized; the
commit only detects some conflicts (`CommitFailedException`). An action cannot call a facade on its own thread, which is
refused with `ReentrantTransitionException`. A hook may start a new call after the commit, which is a new root.

## Concepts that depend on the original machine

Whether these concepts are equivalent depends on how the original machine behaved.

### Rejecting an event that is not permitted

Every state that is not final declares `StatePolicy(state, otherwise = reason)`, and
`Reject(from, transition, reason)` words one identifier; the call returns `Outcome.Rejected` with the code
`NO_TRANSITION` and that reason. With the DSL, `statePolicy(...)` and `reject(...)`. See [policies](graph-model.md#policies-every-node-answers). This is
direct when the original returned a status or a boolean, or ignored the event: a caller that read one now reads
`outcome.status()` or `code()`, and a caller that ignored refusals can keep ignoring them, since nothing is written.

Notes: every state that is not final words a policy (`SG701`), and inside a cascade a refusal aborts the whole call.

What changes, when the original threw an exception: a refused event is returned as an outcome, never thrown. Code that
relied on the exception to stop now carries on as if the move had happened unless it checks `outcome.status()` or
`code()`, and a surrounding transaction that rolled back on that exception no longer does. Check the outcome and throw
your own exception where the old behavior is needed.

### Defining the machine at runtime

`StateGraph.define(MachineKey.of(Entity.class, S.class, T.class))` ... `build()` returns a `GraphDefinition`;
`StateMachines.from(definition)` and `MachineRegistry.builder()` build the machines, and calls go through
`registry.of(key).execute(entity, id)`. See the [programmatic DSL](dsl.md). This is direct when the states and the
events are known when the code compiles and only the graph is assembled in code.

Notes: validation runs in `build()` and throws `InvalidGraphException` with the same `SG` codes instead of failing
javac, so a graph that breaks the structural rules is refused at startup. Guards and actions given as lambdas or method
references need a name, and the DSL has no default hook.

What changes, when the states or the events are only known at runtime, such as names read from configuration or a
database: GraphMaquina needs the states and the events as Java enums, and `MachineKey` refuses any other class. A
machine whose set of states or events changes while the application runs cannot be expressed: adding a state or an
event needs new enums and a new build. The closest pattern turns the configuration into the two enums before
compiling, for example with a generation step of your own build, so each build has a fixed set of states and events;
the edges, guards and actions can then still be assembled at runtime with the DSL.

---

Next: [Migration patterns for concepts without an equivalent](migration-patterns.md). Back to the [documentation index](README.md).
