# Automatic transitions

An automatic edge is one the machine takes on its own. After a commit moves an entity, the entity runs its automatic
chain, unless a failure route that does not continue moved it: while its state has an automatic edge, it makes an
attempt, which runs the first of those edges that its guards admit. `advance(entity)` makes one attempt on request,
for an entity whose data changed without a transition. The guards already say when an entity may go on, so the
application writes no loop of its own: no list of operations to try, no rejections to ignore and no bound against a
loop that never ends.

The library reports and the caller decides. What goes wrong in a chain never throws, an `Error` aside, to the caller
whose call committed: its outcome carries the failure, the abort or the guard that stopped the chain, and the
application answers with its own error or calls `advance` later. The examples come from the onboarding of the
example module, whose tests are in
[OnboardingFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/onboarding/OnboardingFlowTest.java).

## Contents

- [Declare automatic edges](#declare-automatic-edges)
- [One attempt](#one-attempt)
- [When the chain runs](#when-the-chain-runs)
- [Advance an entity](#advance-an-entity)
- [Stops, failures and the limit](#stops-failures-and-the-limit)
- [Failure routes that continue](#failure-routes-that-continue)
- [What the caller sees](#what-the-caller-sees)
- [The application's transaction](#the-applications-transaction)
- [The same graph with the DSL](#the-same-graph-with-the-dsl)
- [Inspect and test](#inspect-and-test)

## Declare automatic edges

An account sets its password through an operation, and every step after that is automatic. The facade,
[Accounts](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/onboarding/Accounts.java), marks those
edges on the interface:

```java
@AccountsGraph.Transition(id = COMPLETE_INFO, from = PASSWORD_SET, to = INFO_SET, automatic = true,
        guards = AccountActionsRefs.InfoComplete.class)
@AccountsGraph.Transition(id = COMPLETE_ADDRESS, from = INFO_SET, to = ADDRESS_SET, automatic = true,
        guards = AccountActionsRefs.AddressComplete.class, action = AccountActionsRefs.VerifyAddress.class,
        recoveries = @AccountsGraph.Recovery(kind = AddressFailures.Unverifiable.class, transition = FLAG,
                continueChain = true))
@AccountsGraph.Transition(id = FLAG, from = INFO_SET, to = FLAGGED)
@AccountsGraph.Transition(id = REQUEST_REVIEW, from = FLAGGED, to = REVIEW, automatic = true)
@AccountsGraph.Transition(id = ACTIVATE_PREMIUM, from = ADDRESS_SET, to = PREMIUM, automatic = true,
        guards = AccountActionsRefs.Premium.class)
@AccountsGraph.Transition(id = ACTIVATE, from = ADDRESS_SET, to = ACTIVE, automatic = true)
@AccountsGraph.AutomaticOrder(from = ADDRESS_SET, transitions = {ACTIVATE_PREMIUM, ACTIVATE})
public interface Accounts extends Machine<Account, AccountStatus, AccountTransition> {

    @AccountsGraph.Transition(id = SET_PASSWORD, from = NEW, to = PASSWORD_SET)
    Outcome<Account, AccountStatus, AccountTransition> setPassword(Account account);
```

`automatic = true` marks the edge from every state of its `from`, whether the `Transition` is written on the interface
or on an operation, which then stays callable by hand. An automatic edge needs no operation: the chain and `advance`
take it, and an `execute`, a cascade or a watch rule can still request its identifier. Here the automatic edges are
`COMPLETE_INFO`, `COMPLETE_ADDRESS`, `REQUEST_REVIEW`, `ACTIVATE_PREMIUM` and `ACTIVATE`; `SET_PASSWORD`, `TOUCH`, a
self-loop, and `CLOSE` are operations, and `FLAG` is a plain failure route.

A state with one automatic edge declares nothing more. A state with two or more declares the order they are tried in,
with the repeatable `AutomaticOrder`, which names each of them once: `ADDRESS_SET` tries `ACTIVATE_PREMIUM` before
`ACTIVATE`. An edge without guards is admitted unless the entity is a child that no parent holds, so it comes last. The
automatic edges, together with the jumps of their continuing failure routes, form no cycle, whatever `allowsCycle` says,
so a chain never comes back to a state on its own. A state that holds a sub-machine has no automatic edge, and an
automatic edge executes no operation that takes an input, since an attempt carries none. The build checks each of these,
as the graph's [structural rules](graph-model.md#structural-rules) list, and `definition().automaticFrom(state)` returns
the automatic edges of a state in the order they are tried.

## One attempt

An attempt is a call on an entity whose state has at least one automatic edge. Its candidates are those edges, in
their order, and each one gets the admission that an `execute` of its identifier would get: the binding check of a
sub-machine, then its guards in declaration order, with the context they would get there, whose `input()` is empty. A
candidate refused by a guard, or as `DETACHED`, passes to the next one. The first one admitted runs within the same
call as any edge runs, without its guards again: its action, its failure routes, its cascades, the starts and exits
of sub-machines, the commit, the hooks and the watch reactions. When no candidate is admitted, the attempt ends in an
`Ignored` with the reason `NO_CANDIDATE_ADMITTED`, writes nothing, and its `rejections()` list each refusal in the
order tried. An attempt never returns `Rejected`, since it requests no identifier.

The guards of the onboarding, in
[AccountActions](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/onboarding/AccountActions.java),
refuse with the reason the caller reads:

```java
@GraphGuard
public GuardResult infoComplete(GuardContext<Account, AccountStatus, AccountTransition> ctx) {
    return ctx.entity().infoComplete() ? GuardResult.pass() : GuardResult.reject("personal info is incomplete");
}

@GraphGuard
public GuardResult addressComplete(GuardContext<Account, AccountStatus, AccountTransition> ctx) {
    return ctx.entity().addressComplete() ? GuardResult.pass() : GuardResult.reject("address is incomplete");
}
```

A guard of a candidate runs once per attempt, and one that throws fails the attempt as it fails an `execute`. The guards
of a refused candidate did run, so keep them free of side effects, as every guard should be. An automatic edge without
guards is admitted whenever an attempt reaches it, unless the entity is a sub-machine that no parent holds, which
refuses every candidate as `DETACHED`: an account never stays in `FLAGGED`, because `REQUEST_REVIEW` takes it on to
`REVIEW` in the same chain. That holds for an edge that also serves as a failure route or as the target of a watch rule:
marked automatic, it is a candidate of every attempt from its origin.

## When the chain runs

After the commit of any root call, each entity with a hop or a sub-machine start in that commit runs its chain, in the
order of the commit's log. Every call counts: an operation, an `execute`, a `reevaluate`, an `advance`, a watch
reaction and an attempt, and so does every entity they move, a peer that a cascade moved, a child that its parent
started and a parent that a child's exit moved. A watch reacts only to a peer whose final state differs from its
original one, but a chain also runs after a self-loop, such as the account's `TOUCH`, and after hops that bring the
entity back where it started: a self-loop often means that data changed.

The chain starts from the state the commit left the entity in, read from the commit's log, not from the entity. While
that state has an automatic edge, it makes an attempt, a root call of its own, which reads the entity's state as any
call does, and goes on from the state that attempt's commit left. It stops after an attempt that admits no candidate,
after one whose failure takes a route that does not continue, after one that aborts, and after one whose own
post-commit ran a call that moved the entity, such as a watch reaction, since that call decided the entity's chain. It
ends without an attempt in a state that has no automatic edge.

![After setPassword commits the account from NEW to PASSWORD_SET, and once any hooks and watch reactions of that commit
ran, the account runs its automatic chain as step 8: COMPLETE_INFO to INFO_SET, COMPLETE_ADDRESS to
ADDRESS_SET and, once ACTIVATE_PREMIUM is refused, ACTIVATE to ACTIVE, each attempt a call of its own that
automaticSteps() lists.](images/automatic-chain.svg)

The chains run as step 8 of [the commit sequence](commit-and-persistence.md#commit-hooks-and-observers), once the
commit's hooks ran and its [watch reactions](coordination.md#watch-other-machines) were dispatched. So every state an
entity commits fires its watches before the entity moves on: an owner that waits for all its items in a state sees
each item there, even one that then goes on by itself.

In [OnboardingFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/onboarding/OnboardingFlowTest.java),
an account whose personal info and address are complete reaches `ACTIVE` in the call that sets its password. The
outcome is the operation's own, to `PASSWORD_SET`, and its `automaticSteps()` list the three attempts that followed,
the third once `ACTIVATE_PREMIUM` refused an account that is not premium. A premium account, whose data is otherwise
the same, ends in `PREMIUM`:

```java
Account account = new Account("a1");
account.setInfoComplete(true);
account.setAddressComplete(true);

Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);

assertThat(outcome).isApplied().movedTo(PASSWORD_SET).hasEntityState(Account::status, ACTIVE);
TypedAutomaticStepsAssert<Account, AccountStatus, AccountTransition> steps =
        assertThat(outcome).automaticSteps().forEntity(AccountsRuntime.KEY, account).hasSize(3);
steps.atIndex(0).isDone().outcome().isApplied().requestedTransition(COMPLETE_INFO).movedTo(INFO_SET);
steps.atIndex(1).isDone().outcome().isApplied().requestedTransition(COMPLETE_ADDRESS).movedTo(ADDRESS_SET);
steps.atIndex(2).isDone().outcome().isApplied().requestedTransition(ACTIVATE).movedTo(ACTIVE);

Account premium = new Account("a2");
premium.setInfoComplete(true);
premium.setAddressComplete(true);
premium.setPremium(true);
accounts.setPassword(premium);
assertEquals(PREMIUM, premium.status());
```

The deepest commit that moved an entity chains it. When a call made on the same thread inside the post-commit, a
hook's call, a watch reaction or an attempt, moved an entity, that call ran or stopped the entity's chain, and the
commit around it does not chain the entity again. A call that a hook starts on another thread, through `async()`,
even one the hook waits for with `join()`, is not nested: the commit around it chains the entity from the state its
own log left it in, its attempt reads the live state, and the chain can go on past a failure route that stopped the
other call's chain. Start calls from a hook with the synchronous facade.
[Records, journals and reactions](outcomes.md#records-journals-and-reactions) says in which outcome each chain is
listed. A new entity has committed nothing, so the automatic edges of its initial state run only when `advance` asks
for them; a child that its parent starts is the exception, since a start counts.

Each attempt is a call of its own. [Observers](commit-and-persistence.md#observers) see it, with the origin
`AUTOMATIC_STEP` and the request `ADVANCE`, inside the `AUTOMATIC_STEPS` phase of the call that triggered it. It has
its own [flow](call-flow.md) and trail, and [logs](logs.md#line-formats) its start as an `advance` with the `trigger=`
of that call. It is bounded by the `asyncTimeout` of its machine from the moment it starts, as a watch reaction is,
and the chain runs on the thread of the call that triggered it: `async().advance(entity)` runs the whole call, its
chains included, on the executor.

## Advance an entity

`advance(entity)` makes one attempt on the entity's current state. The application calls it when it changed data
without a transition. Every facade inherits it from `Machine`, `StateMachine` declares it, and the `advance` of
`AsyncStateMachine` returns a `CompletableFuture`. Its outcome is the attempt's own, `Applied` or `Failed`, and once
the attempt committed, the chain of its entity goes on from there, so `automaticSteps()` starts with the attempt that
followed.

When there is nothing to do, it returns `Ignored` and writes nothing: with `NO_AUTOMATIC_TRANSITION` when the state
has no automatic edge, a terminal state included, where of the machine's callbacks only the state getter ran; and with
`NO_CANDIDATE_ADMITTED`, and its `rejections()`, when every candidate was refused. It never returns `Rejected`. A
failure throws what `execute` would throw, an `UnhandledFailureException` for a domain failure that no route takes
included, and a `null` entity, or a call made where no call may start, is refused before the call starts, as
`reevaluate` refuses them.

In [OnboardingFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/onboarding/OnboardingFlowTest.java),
an account stopped at `INFO_SET` for want of its address goes on once the address arrives, and a second `advance`,
on `ACTIVE`, has nothing left to do:

```java
Account account = new Account("a5");
account.setInfoComplete(true);
accounts.setPassword(account);
account.setAddressComplete(true);

Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.advance(account);

assertThat(outcome).isApplied().requestedTransition(COMPLETE_ADDRESS).hasEntityState(Account::status, ACTIVE)
        .automaticSteps().forEntity(AccountsRuntime.KEY, account).single().isDone().outcome().isApplied()
        .requestedTransition(ACTIVATE);
assertThat(accounts.advance(account)).isIgnored().hasReason(IgnoreReason.NO_AUTOMATIC_TRANSITION);
```

An operation that commits a self-loop does the same with a hop of its own: `touch` moves an account from
`PASSWORD_SET` to itself, and its commit runs the account's chain, as `aSelfLoopRunsTheChainToo` checks.

## Stops, failures and the limit

| What an attempt of a chain ends in | The chain | Its entry in `automaticSteps()` |
| --- | --- | --- |
| The state has no automatic edge | makes no attempt | none |
| No candidate admitted, by a guard or as `DETACHED` | stops | `Done` with the `Ignored` and its `rejections()` |
| `Applied` | goes on from the new state | `Done` with the `Applied` |
| `Failed`, by a route that does not continue | stops in the recovery state | `Done` with the `Failed` |
| `Failed`, by a route that continues | goes on from the recovery state | `Done` with the `Failed` |
| An abort before any write: a domain failure that no route takes, a technical failure or any other exception before the first setter | stops; the entity stays where the attempt found it | `Aborted` with the attempt's exception |
| An abort after a write: a hook threw (`AfterCommitException`) or a setter failed midway (`StatesWritten.PARTIAL`) | stops; the entity may have moved | `Aborted` with the attempt's exception |
| A candidate admitted past `maxAutomaticChain` | stops before the action runs, without writing | `Aborted` with `AutomaticChainExceededException` |
| An `Error` | propagates unchanged to the caller | none |

An attempt whose hook threw runs neither the watch reactions nor the chains of the entities it moved. When the hook
that throws belongs to the call of the application, that call throws `AfterCommitException` and runs no chain at all.
The call of the application follows the rule after a failure too: a `Failed` by a route that does not continue starts
no chain for its entity, which stays in the recovery state until an operation or an `advance` moves it. The rule is
per entity, by the last hop of each in the commit, so the other entities the same commit moved, such as a peer that
the recovery action cascaded to, run their chains.

`maxAutomaticChain(int)`, on [the set builder](coordination.md#build-and-manage-machine-sets) and on
`MachineRegistry.builder()`, bounds the attempts that commit something along one path of continuations from a call of
the application: 10 by default, and at least 1. The k-th attempt of a chain is at level k, the first attempt of an
`advance` at level 1, and a chain nested in an attempt's commit, such as a peer's, starts from that attempt's level, so
twenty items that each take one step after one commit all stay at level 1. The attempt past the limit still selects:
it aborts before the action runs, without writing, when it admits a candidate, and ends `Ignored` as usual when it
admits none, so a chain of exactly `maxAutomaticChain` steps does not abort.

The cycle rule keeps the chain of one entity from coming back to a state on its own, so the limit cuts the paths that
cross other entities: cascades, watches and sub-machines. A watch reaction passes on the automatic level it got, and
an attempt passes on the watch chain it got, so `maxAutomaticChain` and `maxWatchChain` together cut a ping-pong
through cascades or watches. Each attempt also runs one level deeper on the thread's stack than the call that
triggered it, so the stack bounds a chain too, as [build and manage machine sets](coordination.md#build-and-manage-machine-sets)
explains.

A call that a hook starts is a new root: neither limit counts it, and the reactions and attempts it triggers begin
chains of their own. A hook whose call runs that same hook again, through automatic attempts included, therefore
recurs until the thread's stack overflows, as [after-commit hooks](commit-and-persistence.md#after-commit-hooks)
warns; the library keeps no count per thread.

## Failure routes that continue

A failure route, an `onFailure` or a recovery rule, stops its entity's chain by default: the entity stays in the
recovery state until an operation or an `advance` moves it. A route declared to continue lets the chain go on from the
recovery state instead. It may be declared on any edge, automatic or not: when the call of the application fails by
such a route, its entity runs its chain from the recovery state too. With annotations, a `Recovery` takes
`continueChain = true`, as `COMPLETE_ADDRESS` declares above, and a `Transition` takes `onFailureContinues = true`,
which needs an `onFailure`. A continuing route leads to a state with an automatic edge, or the build fails.

The action of `COMPLETE_ADDRESS`, in
[AccountActions](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/onboarding/AccountActions.java),
returns the failure kind that the route takes when the verifier cannot verify the address:

```java
@GraphAction
public ActionOutcome verifyAddress(TransitionContext<Account, AccountStatus, AccountTransition> ctx) {
    return verifier.verifies(ctx.entity())
            ? ActionOutcome.ok()
            : ActionOutcome.failed(AddressFailures.Unverifiable.class, "the address cannot be verified");
}
```

In [OnboardingFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/onboarding/OnboardingFlowTest.java),
the failure takes `FLAG` to `FLAGGED`, the chain goes on through `REQUEST_REVIEW`, and the account ends in `REVIEW`.
The call's outcome is still `Applied`, and it carries the failure:

```java
verifier.refuse();
Account account = new Account("a4");
account.setInfoComplete(true);
account.setAddressComplete(true);

Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);

assertTrue(outcome.automaticFailure().isPresent());
assertThat(outcome).isApplied().hasNoAutomaticBlock().automaticFailure()
        .hasFailureTransition(FLAG).hasFailureKind(AddressFailures.Unverifiable.class).movedTo(FLAGGED);
assertEquals(REVIEW, account.status());
```

## What the caller sees

`automaticSteps()` lists the attempts that the call's own commit triggered, each chain whole and in order, each
attempt an `AutomaticStep.Done` with its outcome or an `AutomaticStep.Aborted` with its cause, as
[records, journals and reactions](outcomes.md#records-journals-and-reactions) details. Three methods of every outcome
search every attempt that ran for the call, those nested in its reactions and in other attempts included, and return
the first that matches: `automaticFailure()` an attempt that ended in `Failed`, whether its route stopped the chain or
let it go on; `automaticBlock()` one that admitted no candidate, whose `rejections()` say why; and `automaticAbort()`
one that aborted. Their types carry wildcards, because the attempts can be of other machines, and
[find what the automatic steps did](outcomes.md#find-what-the-automatic-steps-did) gives their search order.

In [OnboardingFlowTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/onboarding/OnboardingFlowTest.java),
an account without its address stops at `INFO_SET`, and the block says why:

```java
Account account = new Account("a3");
account.setInfoComplete(true);

Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);

assertEquals(INFO_SET, account.status());
Outcome.Ignored<?, ?, ?> block = outcome.automaticBlock().orElseThrow();
assertEquals("address is incomplete", block.rejections().getFirst().reason());
assertThat(outcome).isApplied().automaticBlock().hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED)
        .rejections().single().transition(COMPLETE_ADDRESS).isRejectedByGuardNamed("addressComplete");
```

A guard that stops the chain is no error for the library: the application decides. The library depends on no web
framework, so the example's
[OnboardingController](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/onboarding/OnboardingController.java)
throws its own `BadRequestException`, which a framework's exception handler would turn into a 400: for a call that
was rejected, for an automatic step that failed, even by a route that let the chain go on, and for one that stopped at
a guard. Otherwise it answers with the state the account reached. `theControllerAnswersBadRequest` runs each case:

```java
public AccountStatus setPassword(Account account) {
    Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);
    if (outcome instanceof Outcome.Rejected<Account, AccountStatus, AccountTransition> rejected) {
        throw new BadRequestException(rejected.reason());
    }
    outcome.automaticFailure().ifPresent((Outcome.Failed<?, ?, ?> failure) -> {
        throw new BadRequestException(failure.reason());
    });
    outcome.automaticBlock().ifPresent((Outcome.Ignored<?, ?, ?> block) -> {
        throw new BadRequestException(block.rejections().getFirst().reason());
    });
    return account.status();
}
```

`advance(entity)` goes on from where the entity stands: after a `Failed`, from the recovery state; after an abort
before any write, from the state the attempt started in, which it tries again. An aborted attempt's cause is its
`CallFailedException`, whose `statesWritten()` says whether it wrote: after a setter or a hook threw, the entity may
have moved, and `advance` goes on from whatever state it has.

## The application's transaction

GraphMaquina's commit is in memory. When an automatic step fails or stops at a guard, the call and the steps before
it have committed, and the outcome says so: the controller above throws while the account stays in `INFO_SET` or in
`REVIEW`, as its test checks. If the application throws its error inside a database transaction that rolls back on
that exception, the database undoes the original call and the earlier steps too, although the outcome says they
committed. A Spring `@Transactional` method, for one, rolls back on an unchecked exception by default. To keep the
progress and still answer with an error, configure the transaction so that this exception does not roll it back, or
leave the transaction before you throw. [Framework and database boundaries](commit-and-persistence.md#framework-and-database-boundaries)
covers the rest of what a database transaction does not get from the in-memory commit.

## The same graph with the DSL

[OnboardingDslTest](../graphmaquina-example/src/test/java/io/github/graphmaquina/example/onboarding/OnboardingDslTest.java)
declares the onboarding's graph with the [DSL](dsl.md). It checks that the graph's structural snapshot is the
annotated graph's, and that a machine built from it takes a complete account to `ACTIVE` in the call that sets its
password:

```java
static GraphDefinition<Account, AccountStatus, AccountTransition> graph(AccountActions actions) {
    return StateGraph.define(MachineKey.of(Account.class, AccountStatus.class, AccountTransition.class))
            .initial(NEW).terminal(ACTIVE).terminal(PREMIUM).terminal(CLOSED).allowsCycle(PASSWORD_SET)
            .statePolicy(NEW, "a new account sets its password")
            .statePolicy(PASSWORD_SET, "an account completes its personal info")
            .statePolicy(INFO_SET, "an account completes its address")
            .statePolicy(ADDRESS_SET, "an account is activated")
            .statePolicy(FLAGGED, "a flagged account is reviewed")
            .statePolicy(REVIEW, "a reviewed account is closed")
            .transition(SET_PASSWORD, NEW, PASSWORD_SET).endTransition()
            .transition(TOUCH, PASSWORD_SET, PASSWORD_SET).endTransition()
            .transition(COMPLETE_INFO, PASSWORD_SET, INFO_SET).automatic()
                .guard("infoComplete", actions::infoComplete)
                .endTransition()
            .transition(COMPLETE_ADDRESS, INFO_SET, ADDRESS_SET).automatic()
                .guard("addressComplete", actions::addressComplete)
                .action("verifyAddress", actions::verifyAddress)
                .recoverAndContinue(AddressFailures.Unverifiable.class, FLAG)
                .endTransition()
            .transition(FLAG, INFO_SET, FLAGGED).endTransition()
            .transition(REQUEST_REVIEW, FLAGGED, REVIEW).automatic().endTransition()
            .transition(CLOSE, REVIEW, CLOSED).endTransition()
            .transition(ACTIVATE_PREMIUM, ADDRESS_SET, PREMIUM).automatic()
                .guard("premium", actions::premium)
                .endTransition()
            .transition(ACTIVATE, ADDRESS_SET, ACTIVE).automatic().endTransition()
            .automaticOrder(ADDRESS_SET, List.of(ACTIVATE_PREMIUM, ACTIVATE))
            .build();
}
```

`automatic()` marks the edge it is called on, and a second mark on one edge is `SG507`, like a repeated `timeout`.
`automaticOrder(state, transitions)` declares the order of a state. `recoverAndContinue(kind, transition)` has the
overloads of `recover`, with one throwable type or a list of them, and declares a rule that continues.
`onFailureAndContinue(transition)` is the edge's `onFailure`, declared as a route that continues: declaring it
together with `onFailure` is `SG507`. `build()` checks the rules of
[declare automatic edges](#declare-automatic-edges), and [diagnostics](diagnostics.md) lists their codes. A
`GraphDefinition` written by hand implements `automaticFrom(state)` itself. The runtime asks only that method, so it
must agree with the `automatic()` marks of the edges, which the DSL and the annotations guarantee and a hand-written
definition does not.

## Inspect and test

`TransitionSpec.automatic()` and `onFailureContinues()`, and `RecoveryRule.continueChain()`, read the marks, as
[read a definition](graph-inspection.md#read-a-definition) shows. The [Graphviz export](graph-inspection.md#export-graphviz-dot)
prints `[auto]` after the identifier of a state's only automatic edge, `[auto 1]`, `[auto 2]` and so on, in the
declared order, when there are several, and `continues` in a route that continues. The onboarding's
[golden diagram](../graphmaquina-example/src/test/resources/golden/account.dot) and
[contract snapshot](../graphmaquina-example/src/test/resources/contracts/account.graph) pin its marks and its order.
An eligibility check answers for an automatic edge as for any other. The assertions of `graphmaquina-test` on automatic
edges, on `automaticSteps()` and on the three searches are in [testing samples](testing-advanced.md#automatic-transitions).

---

Next: [Asynchronous work and timeouts](async.md). Back to the [documentation index](README.md).
