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
| SG506 | Retired from the processor along with the row it closed. A non-terminal state with no outgoing edge is SG202 and a state with no rejection policy is SG701. |
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
