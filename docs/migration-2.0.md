# Migrating to GraphMaquina 2.0

2.0 replaces the N×N transition matrix with a graph of identified transitions. **There is no compatibility.**
It is deliberately binary-incompatible with 1.x: no alias of the retired API, no compatibility mode, no
adapter, no flag that restores the old contract, and no silent conversion of anything the old contract wrote.
A project on 1.x does not compile against 2.0 until it is migrated, and that is the intended behaviour: the
failure is a type error at the declaration, not a wrong dispatch at runtime.

This page is the only document of use that still names `Row`, `Cell`, `STAY` and `FORBID`. They appear here
because you have to recognise what you are replacing. They are not supported alternatives, and the rest of
the documentation describes only nodes, edges, guards and policies.

Read it in this order: what maps to what, a whole machine before and after, the four conventions that do not
survive, then the module-by-module detail and the behaviour changes that come with the same release.

## The shape of a machine

Every machine now carries a third type parameter, `T`: a concrete enum, distinct from the state enum, that
identifies its transitions. An edge is `(from: S, transition: T, to: S)` and is uniquely keyed by
`(from, transition)`. Two identifiers may reach the same state, and one identifier may leave several states;
the current state is what disambiguates.

```java
MachineKey<Ticket, TicketState, TicketTransition> key =
        MachineKey.of(Ticket.class, TicketState.class, TicketTransition.class);
```

`MachineKey` equality and hash include the three types. A registry rejects two machines that control the same
entity and state enum with different transition catalogues: two engines would otherwise write the same property.

## What maps to what

| 1.x contract | Migration |
|---|---|
| `Machine<E, S>` and a two-type `MachineKey` | Add the `T` enum and the third parameter to APIs, bindings, peers and every generated type. |
| `Row` and `forbidOthers` | A policy per node, with its reason and per-identifier overrides for identifiers that have no edge. |
| `Cell`, and the `Action` / `Move` operation annotations | One `Transition` with an explicit `id`. |
| `Forbid(from, to)` | `Reject(from, transition)`. Decide by hand which operations that `Forbid` refused: identity is never inferred from a destination. |
| Two methods with the same destination | Give them different identifiers and declare both edges. The accidental dispatch to the other method disappears. |
| `stay`, and `Ignored` when the entity was asked for the state it already held | Declare an explicit self-loop if something must run; it produces `Applied`. If the intent was to do nothing, do not call the operation. |
| `allowedFrom(state)` returning destinations | `transitionsFrom(state)` returns identifiers, structurally. Destinations are read from `outgoingFrom(state)`. |
| `onFailure` and `goTo` naming a state | Both name the identifier of the recovering or reacting edge. |
| A cascade that converged by destination | Resolve the exact edge or refuse. A nested request on an entity already in flight aborts. |
| `render()` and `Detail.FULL` | Export the real graph with Graphviz. There is no tabular substitute. |
| `edge(from, to)` and `/cells` snapshots | `findTransition(from, id)` and `/transitions` in a v2 snapshot. |
| A return to the origin, or a terminal with an ACTION self-loop, in the DSL | A terminal forbids every outgoing edge. A non-terminal self-loop is legal and requires `allowsCycle`. |
| `Outcome.current()` | `Outcome.recordedState()`: the same value, the state the call recorded, not a read of the entity. |
| `TransitionListener` and `listener(...)` on a builder | `MachineObserver` and `observer(...)`, any number of them. A throwable an observer throws, unless it is an `Error`, never fails the call: it is logged. An `Error` still propagates. What must fail the call belongs in a hook, or in `Definition.afterCommit` for every edge of a machine. |
| `AfterCommitException.step()`, `key()`, `entity()`, `from()`, `to()` | `hook()`, and `hop()`: a `TransitionRecord` that also names the identifier. `records()` lists every hop the call committed. |
| `CommitFailedException.pending()` that included the entity whose setter threw | `failedEntity()` names it; `pending()` lists only the entities never tried. |

Preserved, apart from the fixes listed under
[Behaviour changes in this release](#behaviour-changes-in-this-release): guard order, timeouts and deadlines,
no automatic cancellation of external stages, the UnitOfWork with its poison and savepoints, the `COLLECT`
policy, the journal of actions that actually ran, hooks after the setters, one write per entity, the separation
of post-commit reactions, and the exception types of technical failures. Rollback of external side effects was
never promised and still is not.

## A machine, before and after

The same fragment, declared both ways: the `1.x` declaration first, the `2.0` declaration under it.
Both blocks of a pair name the same states, the same operations and the same reasons, so everything that
differs between them is the contract and nothing else.

**Before — a row, a cell, a forbid and two operations:**

```java
@GenerateGraphApi
@TicketsGraph.Definition(initial = OPEN, terminals = {DONE, FAILED},
        state = TicketRefs.State.class, id = TicketRefs.Id.class)
@TicketsGraph.Row(state = OPEN, otherwise = "submit the ticket first")
@TicketsGraph.Row(state = REVIEW, otherwise = "approve or decline the ticket")
@TicketsGraph.Forbid(from = OPEN, to = DONE, reason = "approval requires review")
@TicketsGraph.Cell(from = REVIEW, to = FAILED)
public interface Tickets extends Machine<Ticket, TicketState> {
    @TicketsGraph.Move(from = OPEN, to = REVIEW, guards = TicketActionsRefs.HasTitle.class)
    Outcome<Ticket, TicketState> submit(Ticket ticket);

    @TicketsGraph.Action(from = REVIEW, to = DONE,
            action = TicketActionsRefs.Approve.class, onFailure = FAILED)
    Outcome<Ticket, TicketState> approve(Ticket ticket);
}
```

**After — a policy, a rejection, and edges that carry identifiers:**

```java
@GenerateGraphApi
@TicketsGraph.Definition(initial = OPEN, terminals = {DONE, FAILED},
        state = TicketRefs.State.class, id = TicketRefs.Id.class)
@TicketsGraph.StatePolicy(state = OPEN, otherwise = "submit the ticket first")
@TicketsGraph.StatePolicy(state = REVIEW, otherwise = "approve or decline the ticket")
@TicketsGraph.Reject(from = OPEN, transition = TicketTransition.APPROVE,
        reason = "approval requires review")
@TicketsGraph.Transition(id = TicketTransition.DECLINE, from = REVIEW, to = FAILED)
public interface Tickets extends Machine<Ticket, TicketState, TicketTransition> {
    @TicketsGraph.Transition(id = TicketTransition.SUBMIT, from = OPEN, to = REVIEW,
            guards = TicketActionsRefs.HasTitle.class)
    Outcome<Ticket, TicketState, TicketTransition> submit(Ticket ticket);

    @TicketsGraph.Transition(id = TicketTransition.APPROVE, from = REVIEW, to = DONE,
            action = TicketActionsRefs.Approve.class, onFailure = TicketTransition.DECLINE)
    Outcome<Ticket, TicketState, TicketTransition> approve(Ticket ticket);
}
```

Line by line: `Row` became `StatePolicy` with the same two members; `Forbid(from, to)` became
`Reject(from, transition)` and now names the operation it refuses rather than a destination; `Cell` and the
`Move` / `Action` pair became one `Transition` whose `id` is mandatory and whose absent `action` still means
`MOVE`; `onFailure = FAILED` became `onFailure = TicketTransition.DECLINE`, the identifier of the edge
declared on the interface; and every `Outcome<Ticket, TicketState>` grew its third parameter.

What the declaration no longer says matters as much: there is no diagonal, no closed row and no entry at all
for any pair of states nobody mentioned. Three edges is the whole graph.

**Before — the same flow in the DSL:**

```java
StateGraph.define(key)
        .initial(OPEN).terminal(DONE).terminal(FAILED)
        .from(OPEN)
            .stay()
            .to(REVIEW).move()
            .forbidOthers("submit the ticket first")
        .from(REVIEW)
            .stay()
            .to(DONE).action("approve", actions::approve).onFailure(FAILED)
            .to(FAILED).move()
            .forbidOthers("approve or decline the ticket")
        .build();
```

**After:**

```java
StateGraph.define(key)
        .initial(OPEN).terminal(DONE).terminal(FAILED)
        .statePolicy(OPEN, "submit the ticket first")
        .statePolicy(REVIEW, "approve or decline the ticket")
        .transition(Id.SUBMIT, OPEN, REVIEW).endTransition()
        .transition(Id.APPROVE, REVIEW, DONE)
            .action("approve", actions::approve)
            .onFailure(Id.DECLINE)
            .endTransition()
        .transition(Id.DECLINE, REVIEW, FAILED).endTransition()
        .build();
```

`.from(state)` no longer opens a row, `.stay()` and `.forbidOthers(...)` no longer exist, and each edge is
opened by `transition(id, from, to)` and closed by `endTransition()`. `statePolicy` carries the reason the
row default used to carry, and `reject(state, id, reason)` overrides it for one identifier.

## Four 1.x conventions and where each one goes

1.x had four different ways of talking about a transition from a state to itself, and they did four different
things. None of the four conventions survives, and each one migrates differently. Work through them
separately; treating them as one is the most common way this migration goes wrong.

### 1. The processor's implicit diagonal STAY

**What 1.x did.** For a machine declared with the typed annotations, the generator emitted `.stay()` on every
row. You never wrote it. Every state therefore had a diagonal, and the diagonal was `STAY`. `SG104` refused
any `Cell`, `Action` or `Move` whose `to` was also in its `from`, so an annotated machine could not have any
other kind of self-transition.

*Before:* with the entity in `REVIEW`, `machine.transitionTo(ticket, REVIEW)` evaluated no guards — the row's
diagonal had none — and returned `Outcome.Ignored`, `current() == REVIEW`. No record of an action, no hook,
no state write.

**What 2.0 does.** Nothing is emitted that you did not declare. There is no diagonal.

*After:* there is no edge for `(REVIEW, X)` unless you declared one, so every identifier the node does not
carry is `Outcome.Rejected` with `RejectionKind.NO_TRANSITION` and the node's policy reason. Verifiable:

```java
assertThat(tickets.definition()).hasNoTransition(TicketState.REVIEW, TicketTransition.SUBMIT);
assertThat(tickets.submit(inReview)).isRejected().hasNoTransition()
        .hasReason("approve or decline the ticket");
```

**How to migrate.** If the implicit diagonal only ever produced `Ignored` — which, for an annotated machine,
is all it could produce — there is nothing to declare. Delete the call site: the way to do nothing is not to
ask. If some caller depended on `Ignored` as a success signal, it must now read `Rejected`, or stop calling.

### 2. The DSL's opt-in `.stay()`

**What 1.x did.** In the DSL the diagonal was not implicit: `.from(X).stay()` declared it. It could carry
guards, and those guards ran. If they all passed the step still produced `Ignored` and wrote nothing.

*Before:* `.from(ACTIVE).stay().guard("fresh", guards::fresh)` on an entity in `ACTIVE` ran `fresh`, and on a
pass returned `Outcome.Ignored` with `current() == ACTIVE`. On a rejection it returned `Outcome.Rejected`
naming the guard.

**What 2.0 does.** A transition from a state to itself is an ordinary edge with an identifier. It evaluates
guards, runs its action if it has one, commits, writes the state through the setter and runs the effective
hook.

*After:*

```java
.transition(Id.REFRESH, ACTIVE, ACTIVE)
    .guard("fresh", guards::fresh)
    .action("refresh", actions::refresh)
    .endTransition()
.allowsCycle(ACTIVE)
```

Verifiable — and note that the result kind changed:

```java
assertThat(machine.definition()).transition(ACTIVE, Id.REFRESH).hasTarget(ACTIVE);
assertThat(machine.refresh(entity)).isApplied().movedTo(ACTIVE);
```

**How to migrate.** A `.stay()` that had to run behaviour becomes an identified self-loop: **`Ignored`
becomes `Applied`**. It needs `allowsCycle` on that state, because a self-loop is a cycle. A `.stay()` that
existed only to make a row complete is deleted with the row.

### 3. A DSL diagonal omitted inside a closed row

**What 1.x did.** `.from(X)` … `.forbidOthers("reason")` without a `.stay()` left `(X, X)` as a `FORBID`
cell. Asking for the state the entity already held was refused, **before any guard ran**, with the row's
reason.

*Before:* `Outcome.Rejected`, reason `"complete or fail"`, no guard named, `current()` unchanged.

**What 2.0 does.** The same request is still refused and still runs nothing. What changed is the shape of
the answer: there is no forbidden cell, because there is no cell. The node's policy answers.

*After:* `Outcome.Rejected` with `RejectionKind.NO_TRANSITION`, the policy's reason, and no destination —
`Rejected` does not report a `to`, because one identifier reaches different states from different origins.

```java
assertThat(machine.definition()).policy(ACTIVE).hasDefaultReason("complete or fail");
assertThat(machine.finish(entity)).isRejected().hasNoTransition().hasReason("complete or fail");
```

**How to migrate.** Move the row's `forbidOthers` text into `statePolicy(state, reason)`, and move any
`to(x).forbid(reason)` whose text differed into `reject(state, identifier, reason)` — choosing the
identifier by hand, from the operation that used to be refused, never from the destination that used to be
named. Declaring both an edge and a rejection for one `(from, transition)` is `SG702`.

### 4. A DSL ACTION self-loop

**What 1.x did.** `SG104` only refused a `MOVE` whose `from` equalled its `to`. An **ACTION** on the
diagonal was legal in the DSL, and it behaved like any other action: guards, action, commit, `Applied`. This
is the one case of the four that was already doing the right thing.

*Before:* `.from(ACTIVE).to(ACTIVE).action("touch", actions::touch)` produced `Outcome.Applied` with
`to() == ACTIVE`, a transition record and its hook.

**What 2.0 does.** The same thing, through an identifier.

*After:*

```java
.transition(Id.TOUCH, ACTIVE, ACTIVE)
    .action("touch", actions::touch)
    .afterCommitWith("touched", hooks::touched)
    .endTransition()
.allowsCycle(ACTIVE)
```

```java
assertThat(machine.touch(entity)).isApplied().movedTo(ACTIVE)
        .records().hasSize(1);
```

**How to migrate.** Keep the action and the hook exactly as they are, give the edge an identifier, and add
`allowsCycle` for that state — 1.x did not require it for a diagonal, and 2.0 does, because a self-loop is a
cycle like any other. Two further rules bite here: a terminal may not declare a self-loop at all (`SG202`),
and a non-terminal whose only outgoing edge is its self-loop is legal.

### The summary that matters

| 1.x convention | Where it lived | 1.x result | 2.0 replacement | 2.0 result |
|---|---|---|---|---|
| Implicit diagonal STAY | Annotations, generated | `Ignored` | Nothing. Do not call the operation. | `Rejected · NO_TRANSITION` if you do |
| Opt-in `.stay()` | DSL, explicit | `Ignored`, after its guards | Identified self-loop | **`Applied`** |
| Diagonal omitted in a closed row | DSL, by omission | `Rejected`, the row's reason, no guard | Nothing declared; the node's policy | `Rejected · NO_TRANSITION` |
| ACTION self-loop | DSL only, legal | `Applied` | Identified self-loop plus `allowsCycle` | `Applied`, unchanged |

## Guard or forbid: `RejectionKind` ([#2](https://github.com/graphmaquina/graphmaquina/issues/2))

In 1.x a `Rejected` outcome carried an `Optional<String> guard`, and every consumer that needed to tell a
business guard from a closed cell inspected whether that optional was present. It worked, it was never
written down as a contract, and it was easy to get backwards.

2.0 makes the discriminator explicit. `Rejected` carries `RejectionKind kind`:

| Kind | Meaning | `guard()` |
|---|---|---|
| `NO_TRANSITION` | No edge is keyed by `(from, transition)`. Nothing ran. The reason comes from the node's policy. | Always empty |
| `GUARD` | The edge exists and a guard on it rejected. The reason is the guard's own text. | Always present |

The record enforces the pairing: a `NO_TRANSITION` rejection with a guard, or a `GUARD` rejection without
one, throws `IllegalArgumentException` at construction. In the testing library, `hasNoTransition()` asserts
the first and `isRejectedByGuardNamed(name)` asserts the second — and the latter now checks the kind as well
as the name, which the 1.x `isRejectedByGuardNamed` did not.

`Rejected` also stopped reporting a `to`. There is no destination to report before an edge is resolved, and
inventing one was how a caller could end up believing a route existed.

## Changed semantic expectations

| 1.x behaviour | 2.0 behaviour |
|---|---|
| `transitionTo(entity, targetState)` picks the cell `(current, target)`. | `execute(entity, transition)` resolves `(current, transition)` exactly once. Never by destination. |
| Requesting the state the entity is already in is `Ignored` through the STAY diagonal. | There is no implicit staying. An identifier with no edge from the current state is `Rejected(NO_TRANSITION)`; nothing runs and the state is untouched. To execute something in place, declare an explicit self-loop, which is `Applied`. |
| `Ignored` covers STAY, a convergent cascade no-op and a reevaluation with no rule. | `Ignored` is reserved for reevaluation and says which case it is: `IgnoreReason.NO_WATCH` or `NO_RULE_MATCHED`. |
| A FORBID cell rejects with its reason, and `Rejected` reports a `to`. | A node's `StatePolicy` rejects, with `otherwise` plus reasons declared per identifier. `Rejected` carries `RejectionKind` and the requested identifier, and no destination: the same identifier reaches different states from different origins. |
| `forbidOthers(reason)` closes a row; `to(x).forbid(reason)` forbids one cell. | `statePolicy(state, reason)` gives the node its default reason; `reject(state, transition, reason)` overrides it for one identifier. Declaring both an edge and a rejection for the same key is SG702. |
| A cascade whose peer already holds the requested state is a convergent no-op, recorded with `TransitionRecord.convergent`. | Convergence by destination is gone, and so is `convergent`. A peer that used to converge now resolves the identifier from its own pending state and runs that edge: its guards are evaluated, its action runs, its hook runs and its record is an ordinary one, where 1.x ran none of the three and marked the record `convergent`. If no edge is keyed by that pair, the rejection aborts the whole cascade. A cascade whose peers used to converge therefore does work 1.x skipped, which is what declaring edges instead of choosing them by destination costs. A nested request on an entity already in flight is always `CascadeCycleException`, even when it asks for the transition that entity is running. Once the entity is no longer in flight, the request resolves from its pending state. |
| `CascadeCycleException` reports `inFlightTarget` and `requestedTarget` as states. | It reports `inFlightTransition` and `requestedTransition` as identifiers. |
| `onFailure(state)` routes to the cell `(origin, state)`. | `onFailure(transition)` names the recovering edge and resolves it from the failing edge's origin. A route may legitimately share the destination of the edge it recovers, because identity removes the old ambiguity. Its guards are still skipped and its own route is still not chained. |
| `TransitionAbortedException.to()` always reports a destination. | `transition()` and `to()` are `Optional`: an abort before an edge is resolved reports the requested identifier and no destination, and one raised before any identifier was read, such as a watch chain over its limit, reports neither. Messages read `FROM -ID-> TO`, `FROM -ID-> ?` when no edge was resolved, or `FROM` when no identifier was read. |
| `ActionRecord` and `TransitionRecord` identify a hop by its endpoints and action name. | Both carry the transition identity as well, so two actions with the same name and endpoints stay distinguishable. |
| `CommitContext<S>` carries `from`, `to`, `EdgeKind` and `isTerminal`; `HookRef<E>` takes only the entity. | `CommitContext<S, T>` adds the committed identifier and uses `TransitionKind`. `HookRef<E, S, T>.run(entity, context)` receives the context of the edge that committed; `HookRef.of(name, consumer)` adapts a one-argument hook. |
| A watch rule moves to a state: `.when(c).goTo(state)`. | A watch rule executes a transition of the owner: `.when(c).execute(transition)`. The rule runs that very edge, never another one reaching the same state. |
| `allowedFrom(state)` returns reachable destinations. | `transitionsFrom(state)` returns the identifiers declared from the state. Destinations are inspected through `outgoingFrom(state)`. |
| `GraphDefinition.render()` prints the matrix, and `toString()` delegates to it. | `render()` is gone with the matrix. `toString()` is a summary of name and counts, and DOT lives in `GraphvizExporter`. |
| `EdgeView` describes every cell, and `Detail.FULL` draws STAY and FORBID. | `TransitionView` describes the declared edges only, `PolicyView` describes the node policies, and `GraphView.states()` lists the nodes. `Detail` is gone: there is no mode that draws an edge nobody declared. A policy is a node tooltip, never an arrow. |
| `EdgeKind` distinguishes ACTION, MOVE, STAY and FORBID. | `TransitionKind` distinguishes ACTION from MOVE. Both are real edges; the kind only says whether one runs an action. |

## Retired and changed diagnostics

Three codes are gone, and `SG501` is still not a diagnostic: it does not exist in either release. The codes
that survive keep their numbers; some of them mean something slightly different.

| Code | 2.0 |
|---|---|
| SG101 | Retired from core. There is no N² completeness to check. |
| SG104 | Retired from core. A self-loop is an ordinary edge; it needs `allowsCycle`, not a special kind. In 1.x this code refused a `MOVE` whose `from` equalled its `to` in the DSL, and refused any annotated cell whose `to` was also in its `from`. |
| SG506 | Retired from the processor along with the row it closed. A non-terminal with no outgoing edge is `SG202`; a state with no policy is `SG701`. |
| SG204 | Still rejects an unauthorised cycle, but reports **one witness per cyclic strongly connected component** instead of every elementary cycle. The acceptance rule is unchanged; the number of messages is deliberately smaller. |
| SG301 | Now means: the recovery identifier is this edge's own key, or no edge with that identifier leaves the same origin. |
| SG507, SG511 | Now also cover `onFailure`, `failsOn` or `timeout` declared without the action they need, and a timeout that is not positive. |
| SG701, SG702 | New. A state with no rejection policy; a state that both declares and rejects the same identifier. |

A terminal state with no declared policy gets the stable text `terminal state <NAME> accepts no transitions`; an
explicit `statePolicy` overrides it.

## Retired types

`EdgeKind`, `EdgeSpec`, `MatrixRenderer`, `GraphOutline`, `CellOutline`, `GraphOutlineValidator`, `GraphValidator`,
`RowBuilder`, `EdgeStart`, `ActionEdge`, `AsyncActionEdge`, `MoveEdge`, `StayEdge`, `EdgeView` and `Detail` are
gone from core. The structural model is now `MachineOutline` with `TransitionOutline`, `PolicyOutline` and
`WatchOutline`, validated by `MachineOutlineValidator`, which the DSL and the annotation processor share.

The twelve deprecated matrix annotations — `Action`, `Cell`, `Cells`, `Forbid`, `Forbids`, `Move`, `Row`, `Rows`,
`StateGraph`, `Watch`, `Watches` and `When` — are removed rather than deprecated: a project still using them fails
to resolve the type instead of compiling against a retired contract. `io.github.graphmaquina.dsl.StateGraph`, the
DSL entry point, keeps its name. `VocabularyKind` now reads `DEFINITION, STATE_POLICY, STATE_POLICIES, TRANSITION,
TRANSITIONS, RECOVERY, REJECT, REJECTS, WATCH, WATCHES, WHEN`.

## Generated vocabulary and build artefacts

The per-machine vocabulary the pre-generator emits into `<Machine>Graph` is renamed with the contract it
describes. A project that spelled the old annotations does not compile: the nested types no longer exist.

| 1.x generated annotation | 2.0 |
|---|---|
| `Row` / `Rows` | `StatePolicy` / `StatePolicies`, unchanged members `state` and `otherwise`. |
| `Cell` / `Cells` on the type, `Action` and `Move` on a method | One `Transition` / `Transitions`, repeatable on `TYPE` and `METHOD`. It adds `id()` of the machine's transition enum; an absent `action` still means MOVE, so `Move` has no separate annotation. |
| `Forbid` / `Forbids` with `from` and `to` | `Reject` / `Rejects` with `from` and `transition`. Which operations a `Forbid(from, to)` refused has to be decided by hand: identity is not inferred from a destination. |
| `Action.onFailure` and `Cell.onFailure` name a state | `Transition.onFailure` names a transition of the same enum as `id`. |
| A watch rule moves to a state: `When.goTo` | `When.transition` names an edge of the *owner's* transition enum. A constant of the peer's enum no longer compiles. |

`Definition` is unchanged, including `Class<? extends HookSymbol>[] afterCommit() default {}`: omitting it or
writing `{}` still inherits, and an explicit hook on an edge still replaces it rather than adding to it.

The manifest, the published export index and the compile manifest move to **schema 2**. A `GraphDescriptor`
carries `transitionType` and `transitions` right after `states`, and both are mandatory. A document of schema 1
is refused as SG602 with a message that asks the user to run `clean`, then regenerate and recompile the
producer and the consumer. This also applies to sealed and pending inventories of owned outputs
(`owned-outputs.json` and `owned-outputs.pending.json`): no schema 1 paths are used to delete or migrate files.
Only schema 2 is supported. The user is responsible for migrating source code and cleaning old build outputs.

### Upgrading a build

Run `clean` once, or delete the build directory. The first 2.0 build over a 1.x work directory stops at
`SG602` before it generates anything, because the `annotation-manifest.json` that cycle left behind is a
schema 1 document and 2.0 reads schema 2 only. That is the diagnostic doing its job; the fix is one `clean`.
The schema 1 manifest is left in place, so every build stops there until that `clean` runs.

Producer and consumer move together. A dependency whose published index was written by another tool version is
refused with SG602 just as a schema 1 document is, so a build that upgrades one module at a time stops with a
diagnostic instead of generating against half a graph. Raise the release of `graphmaquina-core`, of the Gradle
or Maven plugin and of `graphmaquina-processor` on the `annotationProcessorPath` in the same change.

Remove stale 1.x binaries by running `clean` or deleting the generated build directory yourself. The 2.0
preparation step refuses schema 1 inventories before changing outputs. For subsequent 2.0 builds, incremental
cleanup continues to use schema 2 inventories and leaves unlisted files untouched. Keep hand-written source
files outside generated output directories before cleaning.

## Annotation processor

The processor reads the schema 2 manifest and the vocabulary above, normalises both to the same `MachineOutline`
the DSL builds, and validates them with `MachineOutlineValidator`. What it generates changed with the contract.

| 1.x generated companion | 2.0 |
|---|---|
| `KEY = MachineKey.of(E.class, S.class)` | `KEY = MachineKey.of(E.class, S.class, T.class)`, and every generic of the companion carries the third parameter. |
| `graph()` builds row by row: `.from(s)`, `.to(s)`, `.stay()`, `.forbidOthers(reason)`. | `graph()` builds edge by edge: `.statePolicy(s, reason)`, `.reject(s, t, reason)` and one `.transition(t, from, to) … .endTransition()` per declared edge, in declaration order. No `.stay()` and no `.forbidOthers(` are emitted, because neither exists. |
| An operation delegates to `stateMachine.transitionTo(entity, S.TARGET[, input])`. | An operation delegates to `stateMachine.execute(entity, T.ID[, input])`. Two operations reaching the same state never substitute for each other. |
| A watch is emitted as `.watches(peerKey, peersOf, ownerOf)` with `.when(c).goTo(state)`. | A watch is emitted as `.watch(state, peerKey, peersOf, ownerOf)` with `.when(c).execute(T.ID)` and `.endWatch()`; the peer key carries the peer's transition enum. |
| The facade implements `allowedFrom(S)` and `<T> CompletableFuture<T> async(Supplier<T>)`. | It implements `Set<T> transitionsFrom(S)` and `<R> CompletableFuture<R> async(Supplier<R>)`. |

### Diagnostics of the processor

| Code | 2.0 |
|---|---|
| SG102 | Now keyed by `(from, id)`, plus a state that declares two policies and a state that rejects one identifier twice. It is never raised for two declarations that merely share endpoints. |
| SG504 | An operation is `Outcome<E, S, T> op(E)` or `op(E, I)` and must carry **at least one** `Transition` of its own vocabulary. The 1.x rule "exactly one of `Action` or `Move`" is gone: a method may open several branches of one identifier. The input restrictions — arity, return type, entity parameter, no generic method, no varargs — are unchanged. |
| SG601 | Now also fires when `Machine<E, S, T>` does not resolve to a concrete entity type and **two distinct enums**. A machine that reuses its state enum as its transition enum is refused here rather than accepted with a degenerate catalogue. |
| SG703 | New. Two annotations of one method name different identifiers, or two methods claim the same identifier. A `TYPE`-level `Transition` may add further branches of an identifier a method owns, as long as their keys are disjoint. |
| SG609 | The retired annotations are recognised by their qualified names — `io.github.graphmaquina.annotation.StateGraph`, `Row`, `Rows`, `Cell`, `Cells`, `Forbid`, `Forbids`, `Action`, `Move`, `Watch`, `Watches`, `When` — and are reported wherever a compilation still resolves them from an older release. They are not kept loadable for the sake of the diagnostic. |

If a source still *imports* one of those twelve types, javac refuses the import before any processor runs and
SG609 is never reached. The replacement of each one is the table in *Generated vocabulary and build artefacts*
above: `Row`→`StatePolicy`, `Cell`/`Action`/`Move`→`Transition`, `Forbid`→`Reject`, and the per-pair
`Watch`/`Watches`/`When` generated for each `@GraphPeers` declaration. Mark the bound members with
`@GraphAction`, `@GraphGuard`, `@GraphHook`, `@GraphCondition` or `@GraphProperty`, and name each of them as
`<Host>Refs.<Member>.class`.

An element a generated annotation requires is still javac's own error, not a diagnostic of this tool. Which
element that is has changed: `Transition.action` has an empty default, because an absent action means the edge
is a move, while `Transition.id` is mandatory. A declaration that omits `id` is `is missing a default value for
the element 'id'`.

## Testing library

`graphmaquina-test` is a published artifact, so every one of these is a source change in a consumer's tests.
Every generic of the library gains the third parameter: `GraphAssert<E, S, T>`, `OutcomeAssert<E, S, T>`,
`TransitionRecordAssert<E, S, T>`, `WatchAssert<E, S, T>`, `ActionProbe<E, S, T>`, `GuardProbe<E, S, T>`,
`ControlledAsyncAction<E, S, T>`, `ControlledAsyncGuard<E, S, T>` and the rest.

| 1.x testing API | 2.0 |
|---|---|
| `GraphAssert.edge(from, to)` returns `EdgeAssert<E, S>`. | `GraphAssert.transition(from, id)` returns `TransitionAssert<E, S, T>`. `EdgeAssert` is gone; there is no view keyed by a destination. |
| `EdgeAssert.isStay()` and `isForbidden()`. | Retired with the kinds. Absence is `GraphAssert.hasNoTransition(from, id)`; a declared self-loop is an ordinary `TransitionAssert` on `(from, id)`. |
| `EdgeAssert.hasReason(text)` reads the reason of a FORBID cell. | `GraphAssert.policy(state)` returns `StatePolicyAssert<S, T>`, with `hasDefaultReason(text)` and `rejects(id, text)`. A reason belongs to a node, not to a cell. |
| `EdgeAssert.hasFailureTarget(state)` / `hasNoFailureTarget()`. | `TransitionAssert.hasFailureTransition(id)` / `hasNoFailureTransition()`. |
| `GraphAssert.hasAllowedTargetsExactly(from, targets…)`. | `GraphAssert.hasTransitionsExactly(from, ids…)`. Two identifiers reaching the same state are two entries, which no set of destinations could express. |
| `BaseOutcomeAssert.requestedTarget(state)` on every variant. | `requestedTransition(id)` and `hasNoRequestedTransition()`. A rejected outcome has no destination to report and an ignored one has no identifier. `FailedOutcomeAssert` keeps the destination it was heading for as `hasTarget(state)` and adds `hasFailureTransition(id)`. |
| `RejectedOutcomeAssert.isForbidden()`. | `hasNoTransition()`, which asserts `RejectionKind.NO_TRANSITION`. `isRejectedByGuardNamed(name)` now also asserts `RejectionKind.GUARD`. |
| `IgnoredOutcomeAssert` carries only the shared expectations. | `hasReason(IgnoreReason)` says whether the state declared no watch or no rule held. |
| `TransitionRecordAssert.isConvergent()` / `isNotConvergent()`. | Retired with `TransitionRecord.convergent`. A hop is identified by `hasTransition(id)` in addition to its endpoints, and `containsExactly` compares the identifier too. |
| `JournalAssert.hasEntry(key, entity, from, to, action, depth, status)`. | `hasEntry(key, entity, from, transition, to, action, depth, status)`. |
| `AbortAssert.at(key, entity, from, to)`. | `at(key, entity, from, transition, to)` for an abort that resolved its edge, `atUnresolved(key, entity, from, transition)` for one refused before resolving any, which reports no destination, and `atUnread(key, entity, from)` for one raised before any identifier was read, such as a chain of watch reactions that exceeds its limit, which reports neither. `AfterCommitAssert.at` gains the identifier the same way: `at(key, entity, from, transition, to)` compares the committed hop the failing hook ran for. |
| `WatchRuleExpectation<S>(condition, target)`. | `WatchRuleExpectation<T>(condition, transition)`: a rule names the identifier it executes. |
| `ActionCall<E, S>(entity, from, to, depth[, input])` and `GuardCall<E, S>(entity, from, to[, input])`. | `ActionCall<E, S, T>(entity, from, transition, to, depth[, input])` and `GuardCall<E, S, T>(entity, from, transition, to[, input])`. The recorded per-invocation input of #5 is unchanged, and `CallInputAssert` is untouched. |
| `GraphGoldens.assertMatches(definition, resource, Detail, options)`. | `Detail` is gone, so the overloads are `(definition, resource)` and `(definition, resource, options)`. The rendering draws the declared edges only. |
| Structural snapshots are `graphmaquina-snapshot-v1` with `/cells/<from>/<to>/…`. | `graphmaquina-snapshot-v2` with `/transitions/<from>/<id>/…`, `/policies/<state>/…`, `/catalog/<index>` for the identifier enum and `/machine/transitions`. A watch rule records `/rules/<i>/transition` instead of `/target`. Absence has no path at all. `GraphSnapshot.parse` refuses a v1 document by name and says to regenerate it; it is never converted. |

`GraphSnapshot.capture`, `GraphSnapshot.parse`, `GraphSnapshot.serialize`, `GraphDiff` and the golden store keep
their signatures and their file formats; inside a snapshot only the paths changed.

## Behaviour changes in this release

Besides the contract above, this release changes behaviour that code written against an earlier one can
observe. Most items refuse up front what used to fail later, in generated code or at runtime, or not at all.

### Runtime

- **Builders are single-use.** Once a build that uses it succeeds, standalone or within a registry, every method of a
  `StateMachines.Builder` throws `IllegalStateException`, and so does every method of `MachineRegistry.Builder` and of a
  generated set's `Builder` once its own `build()` succeeds. A build that fails consumes none of them, so it can be
  fixed and retried. A generated set's `Builder` checks each setting where it is given. In the DSL, an edge's stage
  refuses every call once `endTransition()` closed it, and every stage refuses further declarations once `build()` has
  run.
- **Watch functions may not start transitions on their own thread.** A watch's owner lookup, peer lookup or condition
  that calls `execute` or `reevaluate` gets `ReentrantTransitionException`, reported as `Reaction.Aborted` in a
  reaction. It used to start a root of its own, which `maxWatchChain` did not count.
- **Checked exceptions are handled like unchecked ones.** So is any other throwable that is not an `Error`. One that a
  guard, action, hook, state setter or watch function throws without declaring it, as Kotlin or `@SneakyThrows` code
  can, now goes through a recovery rule or `failsOn`, or aborts, becomes `AfterCommitException` or
  `CommitFailedException`, or, in a watch reaction, aborts the reevaluation that `Reaction.Aborted` reports; one an
  owner lookup throws arrives wrapped in `UndeclaredThrowableException`. It used to escape unchanged.
- **A failed cascade always poisons.** An `Error`, or a throwable from a callback the engine does not wrap, that leaves
  a cascade poisons the unit of work as an engine failure does, even when the action catches it, and an exception
  that a recovery rule or `failsOn` matches after it no longer takes a failure route. `cascadeAll` refuses a null
  element before any element runs, and an
  unknown machine even over an empty collection; an unknown facade in a cascade poisons like an unknown key. What
  poisons without being a failure of the call, an unknown machine or facade, an ambiguous identity, a null peer state,
  reaches the action as an abort that carries it, and that abort ends the call unless the action replaces it with an
  exception of its own.
- **`cascadeAll` reads its collection once.** It copies the collection before any element runs, so elements
  added or removed while the cascade runs change neither which elements run nor their order. It used to fail with
  a `ConcurrentModificationException` of its own iteration or stop early, which could let the root commit a
  partial batch. An exception or an `Error` the collection throws while it is copied propagates as it is and
  poisons nothing.
- **A library exception from a guard or an action is a cause.** A `StateMachineException` that is not a failure of this
  call, and that a guard or an action throws or completes its stage with, bare or wrapped in a `CompletionException` or
  an `ExecutionException`, aborts the call with it as cause, a failure of another call included. It used to reach the
  caller as it was, so another call's abort, or an `AfterCommitException` of another call whose states were written,
  could pass for this call's. For a failure of the call itself, from a cascade, the engine throws it as itself or, when
  the transaction is poisoned, the failure that poisoned the transaction. So when the action lets a
  `PoisonedUnitOfWorkException` through while the transaction is poisoned, the engine throws the failure that poisoned
  the transaction, not that exception.
- **Failures before the state is read are aborts.** The root entity's identity function that throws or returns null, its
  state getter that throws, a null root state, and a watch's peers reader or condition that throws while a `reevaluate`
  resolves end the call in a `TransitionAbortedException` of phase `RESOLVE` that carries the failure; they used to
  escape as themselves. The same failures of a cascaded peer are aborts of phase `ACTION`. A state getter that throws
  during the pre-commit check is a `CommitFailedException` with nothing written. `AbortDetails.from()` and
  `TransitionAbortedException.from()` are `Optional`.
- **Observers replace the listener.** A `MachineObserver`, added with `observer(...)` to a registry or a generated set
  builder as many times as needed, sees every call start and end, failures and `Error`s included. A throwable it throws,
  unless it is an `Error`, is logged and never changes the call; an `Error` propagates. `TransitionListener`,
  `listener(...)` and `TransitionRecorder.compose(...)` are gone, and an `AfterCommitException` now always means that a
  hook failed.
- **Call failures share `CallFailedException`.** `TransitionAbortedException`, `CommitFailedException` and
  `AfterCommitException` extend it and report, as values, the call that failed, its phase, how far it got in
  writing states and the actions that ran. Their constructors take the `MachineCall`.
- **Unbounded timeouts.** An `asyncTimeout` or edge timeout of `Duration.ofNanos(Long.MAX_VALUE)` or more means
  no limit, and the other limit stays in charge. Such budgets used to overflow, into `ArithmeticException` or a
  deadline silently switched off, and an `asyncTimeout` above that left its thread refusing every later
  transition.
- **An async wait runs no other task.** A root waiting on a `ForkJoinPool` worker no longer runs other queued
  tasks on its thread, as `CompletableFuture`'s own wait could, on a common-pool worker for instance, where such a
  task could fail with `ReentrantTransitionException`, keep the wait past its timeout, or cascade through the
  root's context. A task the stage needs must now run on another worker, so on a pool whose other workers are all
  busy, or blocked where the pool cannot see them, the wait can run to its timeout. A pool that refuses to let its
  worker block now aborts the transition, with its `RejectedExecutionException` as the cause; that exception used
  to escape the call.
- **Standalone machines.** `StateMachines.Builder.build()` registers the `as(...)` facade and dispatches the
  machine's own watches; a watch on another machine is refused at `build()`.
- **Blank reasons are refused.** `StatePolicy` refuses a blank reason for an identifier, as it refused a blank
  `otherwise`, and `Outcome.Rejected` and `Outcome.Failed` refuse a blank reason. The reason of a failure that an
  exception of an anonymous class caused names that class by its binary name, where the name used to be empty.
- **Graph utilities and contexts.** `Graph.of` refuses a null node with `NullPointerException`;
  `StrongComponents.cycleWitness` finds a cycle in any set whose induced subgraph holds one, where a set that is
  not a strongly connected component could be refused as holding none; `Graph.inducedSubgraph` and
  `GraphView.terminals()` keep the declared order; `MachineOutlineValidator` no longer takes two names that
  contain `/` for one key; and a context's `peer(Class)` reports a wrong thread, a stale context or a poisoned
  transaction before an unknown facade, as its other calls do.

### Graph declarations

- **New DSL refusals.** `SG507` for an edge never closed with `endTransition()`, which used to be dropped, and
  for an edge that declares more than one action, `onFailure` or `timeout`, where the last one used to win.
  `SG404` for a `when(...)` that no `execute(...)` follows, and an `execute(...)` with no pending `when(...)`.
- **`failsOn`.** `SG302` also refuses a `CancellationException` or one of its subtypes, and
  `CompletionException` and `ExecutionException` themselves, in the DSL and in the processor. A cancellation
  never matched, and the two wrappers are unwrapped before matching whenever they carry a cause.

### Annotation processor

- **Companions carry `@GeneratedCompanion`.** The new
  `io.github.graphmaquina.annotation.internal.GeneratedCompanion`, retained in class files, replaces
  `javax.annotation.processing.Generated`, which the processor no longer claims. A compilation whose only
  annotation is another tool's `@Generated` is no longer refused with `SG602`; under `-Xlint:processing`, javac
  itself warns that no processor claimed that annotation, and `-Werror` turns the warning into an error.
- **Refused by the processor.** Each of these used to be dropped without a word, reported under a
  misleading diagnostic, refused by javac inside the generated companion, or accepted until the set's `build()`
  threw: vocabulary on a default, static or private method, or anywhere but a facade and its abstract operations
  (`SG504`, `SG606`); an abstract method a facade or set inherits from another interface (`SG504`, `SG510`); an
  operation that overrides a `Machine` method, or a redeclaration of one with a result the companion cannot
  return (`SG504`); a parameterized entity (`SG601`, `SG509`); an instance binding of a generic host, and a type
  the companion's package cannot access (`SG505`, `SG509`, `SG510`); two machines of one set that write the
  same entity and state enum (`SG510`); an instance method, declared or inherited, abstract or default, with the
  signature of the companion's static `graph(...)` on a facade (`SG504`) or `builder(...)` on a set (`SG510`),
  the former reported once the facade's declarations have no other error and none of its vocabulary is misplaced,
  the latter only once the set's accessors and machines have no other error; a name the companion writes whose first
  segment a type, member type or constant of its scope takes, so that no spelling reaches it (`SG603`); and a
  machine javac compiled implicitly, from the source path, that the export index or a set names (`SG602`). The index
  case used to publish nothing and report nothing.
- **Companions that javac refused now compile:** in the unnamed package, beside a type of their package or a
  member type of the facade named like a type they write, with parameter names that would have been keywords,
  with deprecated bound members under `-Werror`, with a cast whose type annotations name enum constants, beside
  static overloads of the state and identity accessors, in a set with an accessor named `machineRegistry`, and
  for a bound member or accessor that declares a checked exception, which is now handled as the same exception
  thrown undeclared would be.
- **One problem no longer hides the others.** A failed export check (`SG604`) or a refused companion name no
  longer stops the rest of the compilation from being checked, and javac rerun over the previous companions
  reports them as stale output (`SG602`) instead of a collision (`SG603`).

### Generator and build plugins

- **The generator reads what javac compiles.** The request carries the compilation's release and source
  encoding: the generator parses at that release, reads and writes in that encoding, and refuses syntax the
  release does not have with `SG601`. Gradle source-set patterns and Maven `<includes>` and `<excludes>` are
  honoured, files Ant's default excludes name, such as `._*` copies and `.git` directories, are left out (the
  build guide says where Maven's longer list differs), and symbolic links are followed. See the
  [build guide](typed-annotations-build.md).
- **New generator refusals:** a generic facade, a marker whose annotation type is not on the generator's
  classpath, and a facade, enum, host or peer that the generated code cannot access (`SG601`, `SG604`, `SG608`);
  a type that takes over a name the generated code writes, such as a type of the package named `io`, `java` or
  like a `java.lang` type (`SG603`); a generated type that an original source declares in a file of another
  name, or that differs from one only in case where the volume does not tell names apart (`SG603`); and an
  inventory entry whose file the generator's steps would remove through a symbolic link inside its output root
  that leads out of it, a generated source that would be written through such a link, and an inventory entry
  that no path can spell, such as one holding a NUL character (`SG603`).
- **Declarations the generator misread.** An inner class that extends a sibling inner class, which overflowed the
  stack, a member class of a generic interface, a type named in a declaration's header like a member type the
  declaration declares or inherits, an on-demand import of a type the source cannot access, and a sibling type in
  the unnamed package are resolved as javac resolves them, where generation used to fail or resolve them wrongly.
- **A class of the unnamed package named like a package.** Beside such a class, as a class `shop` beside the
  package `shop`, the generator and the annotation processor read a canonical name as javac does: in code of a
  named package it names the package's type, in code of the unnamed package the class's member type when the
  class has one. Such layouts could get false diagnostics, a processor crash, or wrong output, such as missing
  `*Refs` types, a dropped machine or host, or another enum's states. What a canonical name alone cannot tell
  apart is refused with `SG603`: two machines that share a canonical name, and two types of one canonical name
  that the generator has to tell apart in a class file of the unnamed package, such as two of its supertypes.
- **Builds that used to fail.** A machine renamed only in case on a case-insensitive volume, which got a false
  `SG603`, a compilation id that holds a line break, whose carrier javac refused, and a class output holding an
  unreadable `.class` file, which stopped `prepare-compile` and `complete-compile`, now build.
- **Manifests.** A schema 1 manifest is left in place and refused on every build until `clean`; malformed JSON
  in a manifest or an index is `SG602` naming the file, and so is a `schemaVersion` or a source position that is
  not an exact integer, which used to be truncated. So is an owned source of a manifest that no path can spell,
  such as one holding a NUL character, that has a root, as `\x` and `C:x` have on Windows, or that names the
  directory itself, such as `.`. A number whose exponent is out of range, such as `1e99999999999`, in the request,
  a manifest, an index or an inventory a step needs is `SG602` too, where an unchecked `NumberFormatException`
  used to escape; a processor inventory read only to clean up after an earlier run is then read as listing
  nothing, as one that cannot be read is.
- **A failed pending-manifest write keeps the record.** A `generate` that fails while it first writes its pending
  manifest, on a full disk for instance, leaves `annotation-manifest.json` in place; a later failure deletes it
  but leaves the pending manifest, which still records the previous sources, so either way the next run retires
  and replaces its files once the cause is gone. A failed first write used to delete the manifest, and every
  later run then refused its own previous sources with `SG603` ("would overwrite the unowned file") until
  `clean`.
- **Maven.** `maven.main.skip`, `<skipMain>`, `maven.test.skip` and `<skip>` skip the goals of that compilation
  with an info line instead of failing, and a project packaged as `pom` skips all six. Refused before anything is
  retired: `proc=only`, `-proc:only`, `failOnError=false`, `useIncrementalCompilation=false`, a `-processor`
  list or `<annotationProcessors>` that leaves out `io.github.graphmaquina.processor.StateGraphProcessor` or
  holds a name that is not a valid qualified class name, includes or excludes that leave out the compilation
  carrier, includes that hold only empty values, a test release below 21, a generator `java` with no `javac`
  beside it or whose JDK declares a release below 21, from `javaExecutable` or a toolchain alike, and a generator
  `java` that is not the `java` of the JDK javac compiles with, compared by JDK home, so a wrapper launcher outside
  that JDK's `bin` directory is refused. A `-processor` list or `<annotationProcessors>` that the plugin now
  refuses used to fail only after the preparation had retired the previous outputs, in javac or in the `complete`
  goal, and so could a rebuild without `clean` under `useIncrementalCompilation=false`. The last `-proc:` option
  decides, as for javac, so `-proc:none` followed by a `-proc:full` argument is no longer refused, nor is
  `<proc>none</proc>` with a `-proc:full` argument. The deprecated `<compilerArguments>` map, and for the test
  compilation `<testCompilerArguments>` and `<testCompilerArgument>`, are read as `maven-compiler-plugin` 3.15.0
  passes them, so a manifest argument given only there is no longer refused, and `<compilerArgs>`,
  `<annotationProcessors>`, `<includes>`, `<excludes>`, `<testIncludes>` and `<testExcludes>` written as
  comma-separated text are split as Maven splits them. A `<jdkToolchain>` that matches no toolchain falls back as
  the compiler does, properties given on the command line or to the JVM win over those of the project, and an
  execution's `<compilerArgs>` replace the plugin-level ones, as Maven merges them, where both used to be read.
  The plugin's descriptor declares Java 21 as the Java version it requires.
- **Maven: more refusals.** Also refused before anything is retired: a `-processorpath` or `--processor-path`
  compiler argument, the last one given, that, once evaluated, holds no `${` and does not reach the processor
  through its entries, a jar's manifest `Class-Path`, as a pathing jar's, or the versioned entries a multi-release
  jar holds for the release of javac's JDK, any of them when its `release` file declares none; a
  `--processor-module-path`; with a javac whose JDK declares release 23 or later, a processor that is only a
  dependency when neither `-proc:full`, `-proc:only` nor a `-processor` list requests processing, since such a javac
  then runs no processor it finds on the class path; a `<debuglevel>` or `-g:` arguments that leave out `source`,
  which drops the `SourceFile` attribute; an `<outputDirectory>`, `maven.compiler.outputDirectory`,
  `<multiReleaseOutput>true` or last `-d` argument that moves the classes, and a last `-s` argument that moves the
  processor's sources; `<compileSourceRoots>` without the generated directory; and a `<testIncrementalExcludes>` or
  main `<incrementalExcludes>` pattern that matches the compilation carrier.
- **Maven: settings read as the compiler applies them.** `<testIncrementalExcludes>` narrow the test sources the
  generator reads, so it no longer fails with `SG601` on a source javac never compiles; the compiler's
  `<compileSourceRoots>` are the roots the generator parses, an empty element standing for the project's; an
  empty `<annotationProcessorPaths/>` counts as no processor path, where it used to be refused; a `-processorpath`
  or `--processor-path` argument, the last one given, that reaches the processor is accepted where
  `<annotationProcessorPaths>`, or without one the dependencies, leave the processor out, which used to be refused;
  an empty `<jdkToolchain/>` selects the first jdk toolchain of the build, as the compiler does, where it used to be
  ignored; include and exclude patterns match as plexus-utils matches them, `%ant[...]` and `%regex[...]` included;
  compiler arguments are evaluated as Maven evaluates them, as far as that can be told when the checks run,
  `${settings.localRepository}` for instance, and one that is a single expression Maven turns into nothing, such as
  `${project.file}`, is left out, as javac never gets it; and on Windows a `javaExecutable` without an extension
  stands for the `.exe` beside it.
- **Maven: paths.** A relative manifest path is resolved where javac runs, in the directory Maven was started in
  or, for a forked javac, in the project directory, so a module of a build started from its parent is refused
  up front, where javac used to fail on it after the preparation. Paths are compared with their symbolic links
  resolved, as javac resolves them, so `mvn -f <link>/pom.xml` is checked like a build of the real directory and
  publishes the same source positions, unless the absolute path of `<link>` has fewer names than the `..` that lead
  from the real directory up to a source root outside it: the positions of that root's sources then differ, though,
  read lexically from `<link>`, they still lead to them.
- **Gradle.** `prepare-compile` refuses, before it retires anything: `options.failOnError = false`, `-proc:only`
  or `-proc:none` as the last `-proc:` option, an `options.annotationProcessorPath` that is empty or null, unless
  javac runs from the command line with a `-proc:` argument after it, a `-processor` list, the last one given,
  that leaves out the StateGraph processor or holds a name that is not a valid qualified class name, an empty one
  after its last comma included, a debug setting that leaves out the `SourceFile` attribute, a last `-encoding`
  compiler argument that names another charset than `options.encoding`, when Gradle's own compiler runs javac and
  this JVM supports both, argument providers that change the release or the encoding javac takes, a compilation
  that no longer compiles every generated source, and one that compiles other original sources than the
  generation reads, through a spec or closure filter or a source given to the compile task, which the generation
  also refuses before the generator runs. Sources given to the compile task alone used to build and are now
  refused. Adding `options.failOnError = false` or a compiler argument changes the compile task's options, so
  Gradle's own full rebuild has already removed the previous classes, index and sealed inventory when the
  preparation refuses: unlike Maven's, such a refusal does not keep them. The generation parses only what
  the compile task's include and exclude pattern strings select as well, at the release and in the encoding javac
  takes, a `--release` or `-encoding` in `options.compilerArgs` included, but never one an argument provider
  gives. The generated directory joins the source set as a source directory set of its own, which the source
  set's patterns do not filter, and the sources jar and javadoc leave out the compilation carrier. The generation
  runs after the tasks that build the source set's other directories. Its locations, launcher and generator
  classpath are conventions that `prepare-compile` and `complete-compile` follow, and `classesOutput`,
  `processorSourcesOutput` and `annotationManifest` cannot be set. A processor declaration the build removes no
  longer counts, and the compilation id reads the group the build script settles on, even when something
  realized the generation task before the group was set. What a generator that completes writes on its error
  stream is logged as a warning.

### Testing library

- **`TransitionRecorder` is a `MachineObserver`.** Install it with `observer(recorder)`.
  `compose(...)` is gone: a registry takes any number of observers.
- **`CommitFailureAssert.hasFailedEntity(entity)` and `hasNoFailedEntity()`** are new: they read the entity whose
  setter threw, by reference. `hasPendingExactly(...)` now expects only the entities whose setter was never
  called, since `pending()` no longer lists the one that threw.
- **`AfterCommitAssert` reads the hook and its hop.** `hasHookNamed(name)` replaces `hasStepNamed(name)`,
  `at(key, entity, from, transition, to)` compares the committed hop the failing hook ran for, its identifier
  included, and `records()` reads every hop the call committed.
- **`CallFailureAssert`**, reached through `isCallFailure()`, and every narrowed view of a call failure expect a call
  failure's `hasPhase(...)`, `hasStatesWritten(...)` and `hasCallSameAs(...)`; `journal()` is available on all of them.
- **`AbortAssert.atUnreadState(key, entity[, transition])`** is new: it locates an abort raised before the entity's
  state was read. `at`, `atUnresolved` and `atUnread` now compare `from` as an `Optional`, so a mismatch reports
  `Optional[...]` on both sides, or `Optional.empty` for an abort that read no state.
- **`AbortAssert.atUnread(key, entity, from)`** is new: it locates an abort raised before any identifier was
  read, such as a chain of watch reactions that exceeds its limit.
- **Closed controlled fixtures.** A closed `ControlledAsyncAction` refuses an invocation with
  `GraphAssertionError`, which neither a recovery rule nor a `failsOn` route can take for a failure of the action,
  where it threw `IllegalStateException`; `awaitInvocation` on a closed fixture fails at once, a wait already blocked
  included.
- **`GoldenOptions.defaults()` ignores `graphmaquina.goldens.root` in read mode:** `sourceRoot()` is empty there,
  and a value that is not a path no longer fails a read-mode run.
- **Goldens.** Update mode replaces a source golden that is not valid UTF-8 and writes through a symbolic link
  that stays inside the source root, even one whose target does not exist yet, which it creates with any
  directory missing on the way; a link that leads out of the root is refused with `IllegalArgumentException`, a
  dangling one included, which used to fail with `IllegalStateException`; a leading byte order mark is ignored; a
  resource that names a directory is reported instead of read; and a malformed resource name is reported before
  the system properties are read.
- **Failure reports.** A view opened from another one names the path it came from; a value whose `toString()`
  throws a runtime exception or returns null is still reported; a throwable reported as the actual value is the
  error's cause; and a null element of a list no longer raises `NullPointerException`: an exact comparison reports
  it, and a search skips it.
- **Other fixes.** `GraphDiff.render` escapes a quote inside a value; `AsyncAssertions` waits on a stage that
  cannot be converted to a `CompletableFuture`, without converting or cancelling it; and the outcome of a done
  reaction or of a recorded callback is refused with `IllegalArgumentException` when its recovery identifier
  belongs to another machine's catalogue, where reading that identifier later threw `ClassCastException`.

## What the 2.0 contract keeps

Apart from the fixes listed under [Behaviour changes in this release](#behaviour-changes-in-this-release), these
did not change: guard order, timeouts and deadlines, no automatic cancellation of external stages, the
UnitOfWork with its poison and savepoints, the COLLECT policy, the journal of actions that actually ran, hooks
after the setters, one write per entity, the separation of post-commit reactions, the exception types of
technical failures, and the per invocation operation input of #5 with the default `afterCommit` of #3.
`Graph.cycles()` keeps its public meaning: strongly connected components are used internally for validation and
do not replace it.
