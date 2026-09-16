# Migrating to GraphMaquina 2.0

2.0 replaces the N×N transition matrix with a graph of identified transitions. It is deliberately
binary-incompatible with 1.x: there is no alias of the retired API and no compatibility mode. This page records
the behavioural deltas of the core module — the API, the DSL, the engine and the exporter. Later tasks extend it
with the full symbol-by-symbol table and before/after examples.

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

## Retired diagnostics

| Code | 2.0 |
|---|---|
| SG101 | Retired. There is no N² completeness to check. |
| SG104 | Retired. A self-loop is an ordinary edge; it needs `allowsCycle`, not a special kind. |
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

## What did not change

Guard order, timeouts and deadlines, no automatic cancellation of external stages, the UnitOfWork with its poison
and savepoints, the COLLECT policy, the journal of actions that actually ran, hooks after the setters, one write
per entity, the separation of post-commit reactions, the exception types of technical failures, and the per
invocation operation input of #5 with the default `afterCommit` of #3. `Graph.cycles()` keeps its public meaning:
strongly connected components are used internally for validation and do not replace it.
