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
survive, then the module-by-module detail.

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

Preserved exactly: guard order, timeouts and deadlines, no automatic cancellation of external stages, the
UnitOfWork with its poison and savepoints, the `COLLECT` policy, the journal of actions that actually ran,
hooks after the setters, one write per entity, the separation of post-commit reactions, and the exception
types of technical failures. Rollback of external side effects was never promised and still is not.

## A machine, before and after

The same two-state fragment, declared both ways. `1.x` on the left of each pair, `2.0` on the right.

**Before — a row, a cell, a forbid and two operations:**

```java
@GenerateGraphApi
@TicketsGraph.Definition(initial = OPEN, terminals = {DONE, FAILED},
        state = TicketRefs.Status.class, id = TicketRefs.Id.class)
@TicketsGraph.Row(state = OPEN, otherwise = "start the ticket before completing it")
@TicketsGraph.Row(state = IN_PROGRESS, otherwise = "an active ticket must complete or fail")
@TicketsGraph.Forbid(from = OPEN, to = DONE, reason = "approval requires review")
@TicketsGraph.Cell(from = IN_PROGRESS, to = FAILED)
public interface Tickets extends Machine<Ticket, TicketStatus> {
    @TicketsGraph.Move(from = OPEN, to = IN_PROGRESS, guards = TicketActionsRefs.HasTitle.class)
    Outcome<Ticket, TicketStatus> start(Ticket ticket);

    @TicketsGraph.Action(from = IN_PROGRESS, to = DONE,
            action = TicketActionsRefs.Complete.class, onFailure = FAILED)
    Outcome<Ticket, TicketStatus> complete(Ticket ticket);
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
declared on the interface; and every `Outcome<Ticket, TicketStatus>` grew its third parameter.

What the declaration no longer says matters as much: there is no diagonal, no closed row and no entry at all
for any pair of states nobody mentioned. Three edges is the whole graph.

**Before — the same flow in the DSL:**

```java
StateGraph.define(key)
        .initial(OPEN).terminal(DONE).terminal(FAILED)
        .from(OPEN)
            .stay()
            .to(IN_PROGRESS).move()
            .forbidOthers("start before completing")
        .from(IN_PROGRESS)
            .stay()
            .to(DONE).action("complete", actions::complete).onFailure(FAILED)
            .to(FAILED).move()
            .forbidOthers("complete or fail")
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

*Before:* with the entity in `IN_PROGRESS`, `machine.transitionTo(ticket, IN_PROGRESS)` evaluated no guards
— the row's diagonal had none — and returned `Outcome.Ignored`, `current() == IN_PROGRESS`. No record of an
action, no hook, no state write.

**What 2.0 does.** Nothing is emitted that you did not declare. There is no diagonal.

*After:* there is no edge for `(REVIEW, X)` unless you declared one, so every identifier the node does not
carry is `Outcome.Rejected` with `RejectionKind.NO_TRANSITION` and the node's policy reason. Verifiable:

```java
assertThat(tickets.definition()).hasNoTransition(TicketState.REVIEW, TicketTransition.SUBMIT);
assertThat(tickets.submit(inReview)).isRejected().hasNoTransition()
        .hasReason("approve, reject or retouch the ticket");
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
| A cascade whose peer already holds the requested state is a convergent no-op, recorded with `TransitionRecord.convergent`. | Convergence by destination is gone, and so is `convergent`. A nested request on an entity already in flight is always `CascadeCycleException`, even when it asks for the transition that entity is running. Once the entity is no longer in flight, the request resolves from its pending state. |
| `CascadeCycleException` reports `inFlightTarget` and `requestedTarget` as states. | It reports `inFlightTransition` and `requestedTransition` as identifiers. |
| `onFailure(state)` routes to the cell `(origin, state)`. | `onFailure(transition)` names the recovering edge and resolves it from the failing edge's origin. A route may legitimately share the destination of the edge it recovers, because identity removes the old ambiguity. Its guards are still skipped and its own route is still not chained. |
| `TransitionAbortedException.to()` always reports a destination. | `transition()` and `to()` are `Optional`: an abort before an edge is resolved invents neither. Messages read `FROM -ID-> TO`, or `FROM` when nothing was resolved yet. |
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
TRANSITIONS, REJECT, REJECTS, WATCH, WATCHES, WHEN`.

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
is refused as SG602 with a message that asks for the producer and the consumer to be regenerated and recompiled;
it is never converted and never read half-way. The single exception is the inventory of owned outputs
(`owned-outputs.json`), which carries paths and no graph: a previous cycle's inventory is still read, solely so
the outputs that cycle owned can be deleted. `clean` is always a complete migration path.

### Upgrading a build

Run `clean` once, or delete the build directory. The first 2.0 build over a 1.x work directory stops at
`SG602` before it generates anything, because the `annotation-manifest.json` that cycle left behind is a
schema 1 document and 2.0 reads schema 2 only. That is the diagnostic doing its job; the fix is one `clean`.

Producer and consumer move together. A dependency whose published index was written by another tool version is
refused with SG602 just as a schema 1 document is, so a build that upgrades one module at a time stops with a
diagnostic instead of generating against half a graph. Raise the release of `graphmaquina-core`, of the Gradle
or Maven plugin and of `graphmaquina-processor` on the `annotationProcessorPath` in the same change.

The stale binaries of the retired vocabulary — `<Machine>Graph$Row.class`, `$Rows.class`, `$Cell.class`,
`$Cells.class` and the rest — are removed by the preparation step of the next build, from the inventory the
previous cycle sealed and never from a name pattern. A class file that inventory does not list is left exactly
as it is, whatever it is called, so hand-written classes beside the generated ones survive the upgrade. Deleting
the build directory reaches the same state.

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
| `AbortAssert.at(key, entity, from, to)`. | `at(key, entity, from, transition, to)` for an abort that resolved its edge, and `atUnresolved(key, entity, from, transition)` for one refused before resolving any, which reports no destination. `AfterCommitAssert.at` is unchanged: the exception still names the committed hop by its endpoints. |
| `WatchRuleExpectation<S>(condition, target)`. | `WatchRuleExpectation<T>(condition, transition)`: a rule names the identifier it executes. |
| `ActionCall<E, S>(entity, from, to, depth[, input])` and `GuardCall<E, S>(entity, from, to[, input])`. | `ActionCall<E, S, T>(entity, from, transition, to, depth[, input])` and `GuardCall<E, S, T>(entity, from, transition, to[, input])`. The recorded per-invocation input of #5 is unchanged, and `CallInputAssert` is untouched. |
| `GraphGoldens.assertMatches(definition, resource, Detail, options)`. | `Detail` is gone, so the overloads are `(definition, resource)` and `(definition, resource, options)`. The rendering draws the declared edges only. |
| Structural snapshots are `graphmaquina-snapshot-v1` with `/cells/<from>/<to>/…`. | `graphmaquina-snapshot-v2` with `/transitions/<from>/<id>/…`, `/policies/<state>/…`, `/catalog/<index>` for the identifier enum and `/machine/transitions`. A watch rule records `/rules/<i>/transition` instead of `/target`. Absence has no path at all. `GraphSnapshot.parse` refuses a v1 document by name and says to regenerate it; it is never converted. |

`GraphSnapshot.capture`, `GraphSnapshot.parse`, `GraphSnapshot.serialize`, `GraphDiff` and the golden store keep
their signatures and their formats; only the paths inside a snapshot changed.

## What did not change

Guard order, timeouts and deadlines, no automatic cancellation of external stages, the UnitOfWork with its poison
and savepoints, the COLLECT policy, the journal of actions that actually ran, hooks after the setters, one write
per entity, the separation of post-commit reactions, the exception types of technical failures, and the per
invocation operation input of #5 with the default `afterCommit` of #3. `Graph.cycles()` keeps its public meaning:
strongly connected components are used internally for validation and do not replace it.
