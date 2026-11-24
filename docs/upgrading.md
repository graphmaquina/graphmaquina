# Upgrading

Each release since 2.0.5 replaced something applications wrote around a gap with a feature of the library. Read the
sections from the version after yours up to the one you move to: each says what a workaround looks like, what replaces
it, and what no longer compiles.

## Contents

- [2.0.8](#208)
- [2.0.7](#207)
- [2.0.6](#206)
- [2.0.5](#205)

## 2.0.8

| If your code does this | Use this instead |
| --- | --- |
| A self-loop with `allowsCycle` and an operation nobody may call, to ask whether something is allowed | A [gate](edge-kinds.md#a-gate-ask-a-permission-without-moving) and a `Check` for it |
| An edge the application promises never to call, so that a state another system writes is reachable | An [external edge](edge-kinds.md#an-external-edge-a-move-another-system-makes) |
| `Instant.now()` or an injected clock in guards and actions, or the time passed as the operation's input | [`ctx.now()`](guard-evidence.md#read-the-time-of-the-call) and the builder's `clock(InstantSource)` |
| A service's answer cached in the operation's input or a thread-local, so that two guards read it once | [`ctx.memo`](guard-evidence.md#read-evidence-once-per-request) with a `MemoKey` |
| `ctx.input(Type.class)` in a try block, or an `instanceof` on `ctx.input()`, in a guard shared by several operations | [`ctx.inputIfInstance(Type.class)`](guard-evidence.md#read-an-input-of-one-of-several-types) |
| The cause chain of a `TransitionAbortedException` searched for what a guard threw | `guardFailure()`, or the guard's [`propagates`](guard-evidence.md#when-reading-the-evidence-fails) so the call throws it itself |
| The cause chain searched for an `UninitializedStateException` | `uninitializedState()`, as [runtime exceptions](runtime-exceptions.md) lists |
| A `switch` over the identifier that calls the facade's operations or checks | The facade's `execute(entity, id)` and `eligibility(entity, id)`, as the [graph model](graph-model.md#operations-and-internal-edges) says |
| `outcome.status() == OutcomeStatus.APPLIED` | `outcome.isApplied()` |
| The rejections of a `NoneAdmitted` searched for a guard's refusal | `refusedByGuard()`, as [choices](choices.md#check-a-choice) shows |
| One `StatePolicy` per state, all with the same reason | One `StatePolicy(state = {A, B}, otherwise = ...)` |
| A dependency's jar unzipped to read its `exports.json` | The [`listStateGraphExports` task or the `list-exports` goal](cross-module-composition.md#see-what-a-dependency-exports) |

What no longer compiles or builds:

- `recordTrail(InstantSource)` is removed from the machine builder and from the generated set builder: call
  `clock(InstantSource)` and `recordTrail()`.
- `GuardContext` declares `now()` and `memo(key, supplier)`, and `TransitionContext` declares `now()`: a class of yours
  that implements either context, such as a fake in a unit test, implements them.
- Every facade inherits `execute(entity, transition)` and `eligibility(entity, transition)` from `Machine`: a facade
  method with one of those signatures is `SG504`.
- `Transition` gains `gate` and `external`, `StatePolicy.state` is an array, and `GraphGuard` gains `propagates`; an
  annotation that names one state still compiles.

## 2.0.7

| If your code does this | Use this instead |
| --- | --- |
| A guard's name repeated as a string literal | The `NAME` constant of the guard's generated symbol |
| Automatic edges kept manual, or guarded by a flag, so they run only when the application asks | `automaticChain = AutomaticChain.ON_ADVANCE` and `advance(entity)`, as [chain only on advance](automatic-transitions.md#chain-only-on-advance) says |
| Operations called one after the other, or their guards repeated in an `if`, to run the edge the data means | A [choice](choices.md) |
| Another machine's guards repeated in a guard, to admit an edge only while that machine would admit an operation | A [check started from the guard](eligibility.md#check-another-machine-from-a-guard) |

## 2.0.6

| If your code does this | Use this instead |
| --- | --- |
| A graph built with stand-in collaborators only to inspect or draw it | The companion's `structure()`, which takes none |
| A main method of your own that writes a machine's DOT | The `exportStateGraphDot` task or the `export-dot` goal, as [export from the command line](graph-inspection.md#export-from-the-command-line) says |
| The DOT text rewritten to change its colors | A [`GraphvizTheme`](graph-inspection.md#change-the-colors) |

A facade method that clashes with the companion's `structure()` is refused.

## 2.0.5

| If your code does this | Use this instead |
| --- | --- |
| No `import module` declaration in the sources the generator reads | `import module` of a JDK module, whose types the generator resolves, as [build troubleshooting](build-troubleshooting.md) says |

---

Next: [Migrate a machine from another library](migrate-from-another-library.md). Back to the [documentation index](README.md).
