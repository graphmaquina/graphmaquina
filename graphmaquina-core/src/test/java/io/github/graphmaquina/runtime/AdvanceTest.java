package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AccountGraphs.ADDRESS_INCOMPLETE;
import static io.github.graphmaquina.fixtures.AccountGraphs.machine;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountStatus.ACTIVE;
import static io.github.graphmaquina.fixtures.AccountStatus.ADDRESS_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.FLAGGED;
import static io.github.graphmaquina.fixtures.AccountStatus.INFO_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.PREMIUM;
import static io.github.graphmaquina.fixtures.AccountTransition.ACTIVATE;
import static io.github.graphmaquina.fixtures.AccountTransition.ACTIVATE_PREMIUM;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_ADDRESS;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_INFO;
import static io.github.graphmaquina.fixtures.AccountTransition.FLAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressFailures;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * {@code advance(entity)} makes one automatic attempt: it tries the automatic edges of the entity's state in the
 * order {@code automaticFrom} lists them, each with the admission of an execute of its identifier, and runs the first
 * one admitted as execute would. Otherwise it ends {@code Ignored}, never {@code Rejected}:
 * {@code NO_AUTOMATIC_TRANSITION} when the state has no automatic edge, and {@code NO_CANDIDATE_ADMITTED}, with each
 * rejection, when no candidate was admitted. Its failures throw as execute's do. The onboarding of the account fixture
 * shows what a graph declares; a lane machine, whose guards are probes, counts what runs.
 */
class AdvanceTest {

    /** States of a lane: it waits, then is made ready and leaves fast or slow, or is parked while it waits. */
    enum LaneStatus { WAITING, READY, FAST, SLOW, PARKED }

    /** Identifiers of the lane machine. */
    enum LaneTransition { PREPARE, PARK, GO_FAST, GO_SLOW }

    /** States of a holder, which holds its lane while it is HOLDING. */
    enum HolderStatus { OPEN, HOLDING, DONE, CANCELLED }

    /** Identifiers of the holder machine. */
    enum HolderTransition { HOLD, FINISH, CANCEL }

    static final MachineKey<Lane, LaneStatus, LaneTransition> LANES =
            MachineKey.of(Lane.class, LaneStatus.class, LaneTransition.class);

    static final MachineKey<Holder, HolderStatus, HolderTransition> HOLDERS =
            MachineKey.of(Holder.class, HolderStatus.class, HolderTransition.class);

    /** A lane: an id and a mutable status. */
    @Getter
    static final class Lane {

        private final String id;
        private LaneStatus status;

        Lane(String id, LaneStatus status) {
            this.id = id;
            this.status = status;
        }

        void setStatus(LaneStatus status) { this.status = status; }

        @Override
        public String toString() { return "Lane#" + id; }
    }

    /** The parent of one lane, which holds it while it is HOLDING. */
    @Getter
    static final class Holder {

        private final String id;
        private HolderStatus status;
        private final Lane lane;

        Holder(String id, HolderStatus status, Lane lane) {
            this.id = id;
            this.status = status;
            this.lane = lane;
        }

        void setStatus(HolderStatus status) { this.status = status; }
    }

    private final AddressVerifier verifier = new AddressVerifier();
    private final StateMachine<Account, AccountStatus, AccountTransition> accounts = machine(onboarding(verifier));

    private static Account account(AccountStatus status) {
        return new Account("a1", status);
    }

    private static Account complete(Account account) {
        account.setAddressComplete(true);
        return account;
    }

    /** A lane guard probe that rejects every call with {@code reason}. */
    private static GuardProbe<Lane, LaneStatus, LaneTransition> rejecting(String reason) {
        GuardProbe<Lane, LaneStatus, LaneTransition> probe = new GuardProbe<>();
        probe.verdict = (GuardContext<Lane, LaneStatus, LaneTransition> ctx) -> GuardResult.reject(reason);
        return probe;
    }

    /**
     * WAITING leaves by PREPARE to READY, automatic only when {@code automaticPrepare}, or by PARK to PARKED, guarded
     * by {@code park}; READY tries GO_FAST to FAST, guarded by {@code fast}, then GO_SLOW to SLOW, guarded by
     * {@code slow}, both automatic. FAST, SLOW and PARKED are terminal.
     */
    private static GraphDefinition<Lane, LaneStatus, LaneTransition> lanes(
            GuardProbe<Lane, LaneStatus, LaneTransition> fast, GuardProbe<Lane, LaneStatus, LaneTransition> slow,
            GuardProbe<Lane, LaneStatus, LaneTransition> park, boolean automaticPrepare) {
        TransitionBuilder<Lane, LaneStatus, LaneTransition> prepare = StateGraph.define(LANES)
                .initial(LaneStatus.WAITING)
                .terminal(LaneStatus.FAST).terminal(LaneStatus.SLOW).terminal(LaneStatus.PARKED)
                .statePolicy(LaneStatus.WAITING, "a waiting lane is prepared or parked")
                .statePolicy(LaneStatus.READY, "a ready lane goes fast or slow")
                .transition(LaneTransition.PREPARE, LaneStatus.WAITING, LaneStatus.READY);
        return (automaticPrepare ? prepare.automatic() : prepare).endTransition()
                .transition(LaneTransition.PARK, LaneStatus.WAITING, LaneStatus.PARKED).guard("park", park)
                    .endTransition()
                .transition(LaneTransition.GO_FAST, LaneStatus.READY, LaneStatus.FAST).automatic().guard("fast", fast)
                    .endTransition()
                .transition(LaneTransition.GO_SLOW, LaneStatus.READY, LaneStatus.SLOW).automatic().guard("slow", slow)
                    .endTransition()
                .automaticOrder(LaneStatus.READY, List.of(LaneTransition.GO_FAST, LaneTransition.GO_SLOW))
                .build();
    }

    /** {@link #lanes(GuardProbe, GuardProbe, GuardProbe, boolean)} with PREPARE manual and a passing park guard. */
    private static StateMachine<Lane, LaneStatus, LaneTransition> lanes(
            GuardProbe<Lane, LaneStatus, LaneTransition> fast, GuardProbe<Lane, LaneStatus, LaneTransition> slow) {
        return laneMachine(lanes(fast, slow, new GuardProbe<>(), false));
    }

    private static StateMachine<Lane, LaneStatus, LaneTransition> laneMachine(
            GraphDefinition<Lane, LaneStatus, LaneTransition> graph) {
        return StateMachines.from(graph).state(Lane::status, Lane::setStatus).identity(Lane::id).build();
    }

    /** Holders, which hold their lane in HOLDING, and lanes over {@code lanes}, every lane's parent being holder. */
    private static MachineRegistry held(Holder holder, GraphDefinition<Lane, LaneStatus, LaneTransition> lanes) {
        GraphDefinition<Holder, HolderStatus, HolderTransition> holders = StateGraph.define(HOLDERS)
                .initial(HolderStatus.OPEN).terminal(HolderStatus.DONE).terminal(HolderStatus.CANCELLED)
                .statePolicy(HolderStatus.OPEN, "a holder holds its lane")
                .statePolicy(HolderStatus.HOLDING, "a holder waits for its lane")
                .transition(HolderTransition.HOLD, HolderStatus.OPEN, HolderStatus.HOLDING).endTransition()
                .transition(HolderTransition.FINISH, HolderStatus.HOLDING, HolderStatus.DONE).endTransition()
                .transition(HolderTransition.CANCEL, HolderStatus.HOLDING, HolderStatus.CANCELLED).endTransition()
                .submachine(HolderStatus.HOLDING, lanes, (Holder each) -> Optional.of(each.lane()),
                        (Lane lane) -> Optional.of(holder))
                    .exit(LaneStatus.FAST, HolderTransition.FINISH).exit(LaneStatus.SLOW, HolderTransition.FINISH)
                    .exit(LaneStatus.PARKED, HolderTransition.CANCEL).endSubmachine()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(holders).state(Holder::status, Holder::setStatus).identity(Holder::id))
                .add(StateMachines.from(lanes).state(Lane::status, Lane::setStatus).identity(Lane::id))
                .build();
    }

    @Test
    void advanceRunsTheOnlyAutomaticEdge() {
        Account account = account(PASSWORD_SET);
        account.setInfoComplete(true);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.advance(account);
        Outcome.Applied<Account, AccountStatus, AccountTransition> applied = outcome.asApplied().orElseThrow();
        assertEquals(PASSWORD_SET, applied.from());
        assertEquals(COMPLETE_INFO, applied.transition());
        assertEquals(INFO_SET, applied.to());
        assertEquals(Optional.of(COMPLETE_INFO), outcome.requestedTransition());
        assertEquals(1, outcome.records().size());
        assertEquals(INFO_SET, account.status());
    }

    @Test
    void advanceTriesCandidatesInDeclaredOrder() {
        Account basic = account(ADDRESS_SET);
        Account premium = new Account("a2", ADDRESS_SET);
        premium.setPremium(true);
        Outcome<Account, AccountStatus, AccountTransition> activated = accounts.advance(basic);
        Outcome<Account, AccountStatus, AccountTransition> upgraded = accounts.advance(premium);
        assertEquals(OutcomeCode.APPLIED, activated.code());
        assertEquals(Optional.of(ACTIVATE), activated.requestedTransition());
        assertEquals(ACTIVE, basic.status());
        assertEquals(OutcomeCode.APPLIED, upgraded.code());
        assertEquals(Optional.of(ACTIVATE_PREMIUM), upgraded.requestedTransition());
        assertEquals(PREMIUM, premium.status());
    }

    /** The guard of a rejected candidate runs once, and so does the guard of the admitted one, never again. */
    @Test
    void aRejectedCandidateRunsItsGuardOnce() {
        GuardProbe<Lane, LaneStatus, LaneTransition> fast = rejecting("the fast lane is closed");
        GuardProbe<Lane, LaneStatus, LaneTransition> slow = new GuardProbe<>();
        Outcome<Lane, LaneStatus, LaneTransition> outcome = lanes(fast, slow).advance(new Lane("l1", LaneStatus.READY));
        assertEquals(Optional.of(LaneTransition.GO_SLOW), outcome.requestedTransition());
        assertEquals(1, fast.calls);
        assertEquals(1, slow.calls);
    }

    /** A candidate's guard sees what it would see in an execute of its identifier: the edge, and no input. */
    @Test
    void theGuardContextIsTheExecuteContext() {
        List<String> seen = new ArrayList<>();
        GuardProbe<Lane, LaneStatus, LaneTransition> slow = new GuardProbe<>();
        slow.verdict = (GuardContext<Lane, LaneStatus, LaneTransition> ctx) -> {
            seen.add(ctx.entity() + " " + ctx.from() + " -" + ctx.transition() + "-> " + ctx.to() + " " + ctx.input());
            return GuardResult.pass();
        };
        StateMachine<Lane, LaneStatus, LaneTransition> machine = lanes(rejecting("the fast lane is closed"), slow);
        machine.advance(new Lane("l1", LaneStatus.READY));
        machine.execute(new Lane("l2", LaneStatus.READY), LaneTransition.GO_SLOW);
        assertEquals(List.of("Lane#l1 READY -GO_SLOW-> SLOW Optional.empty",
                "Lane#l2 READY -GO_SLOW-> SLOW Optional.empty"), seen);
    }

    /**
     * A state without automatic edges, a terminal one included, ends the advance once its state is read: no guard of
     * a manual edge runs, and not even the identity function.
     */
    @Test
    void noAutomaticEdgeIsIgnoredWithoutUserCodeBeyondStateAndIdentity() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger identities = new AtomicInteger();
        GuardProbe<Lane, LaneStatus, LaneTransition> park = new GuardProbe<>();
        StateMachine<Lane, LaneStatus, LaneTransition> machine =
                StateMachines.from(lanes(new GuardProbe<>(), new GuardProbe<>(), park, false))
                        .state((Lane lane) -> {
                            reads.incrementAndGet();
                            return lane.status();
                        }, Lane::setStatus)
                        .identity((Lane lane) -> {
                            identities.incrementAndGet();
                            return lane.id();
                        })
                        .build();
        Outcome.Ignored<Lane, LaneStatus, LaneTransition> ignored =
                machine.advance(new Lane("l1", LaneStatus.WAITING)).asIgnored().orElseThrow();
        assertEquals(IgnoreReason.NO_AUTOMATIC_TRANSITION, ignored.reason());
        assertEquals(OutcomeCode.NO_AUTOMATIC_TRANSITION, ignored.code());
        assertEquals(LaneStatus.WAITING, ignored.from());
        assertEquals(List.of(), ignored.rejections());
        assertEquals(Optional.empty(), ignored.explanation());
        assertEquals(Optional.empty(), ignored.requestedTransition());
        assertEquals(List.of(1, 0, 0), List.of(reads.get(), identities.get(), park.calls));
        assertEquals(IgnoreReason.NO_AUTOMATIC_TRANSITION,
                machine.advance(new Lane("l2", LaneStatus.FAST)).asIgnored().orElseThrow().reason());
    }

    @Test
    void noCandidateAdmittedListsEachRejection() {
        Account account = account(INFO_SET);
        Outcome.Ignored<Account, AccountStatus, AccountTransition> single =
                accounts.advance(account).asIgnored().orElseThrow();
        assertEquals(IgnoreReason.NO_CANDIDATE_ADMITTED, single.reason());
        assertEquals(OutcomeCode.NO_CANDIDATE_ADMITTED, single.code());
        assertEquals(List.of(new Eligibility.Ineligible<>(account, INFO_SET, COMPLETE_ADDRESS, ADDRESS_INCOMPLETE,
                RejectionKind.GUARD, Optional.of("addressComplete"))), single.rejections());
        assertEquals(Optional.of("no automatic transition was admitted: COMPLETE_ADDRESS (GUARD addressComplete):"
                + " address is incomplete"), single.explanation());
        assertEquals(List.of(), single.records());
        assertEquals(FlowView.empty(), single.flow());
        assertEquals(List.of(INFO_SET, 0), List.of(account.status(), verifier.calls));

        Lane lane = new Lane("l1", LaneStatus.READY);
        Outcome.Ignored<Lane, LaneStatus, LaneTransition> both = lanes(rejecting("the fast lane is closed"),
                rejecting("the slow lane is closed")).advance(lane).asIgnored().orElseThrow();
        assertEquals(List.of(
                new Eligibility.Ineligible<>(lane, LaneStatus.READY, LaneTransition.GO_FAST, "the fast lane is closed",
                        RejectionKind.GUARD, Optional.of("fast")),
                new Eligibility.Ineligible<>(lane, LaneStatus.READY, LaneTransition.GO_SLOW, "the slow lane is closed",
                        RejectionKind.GUARD, Optional.of("slow"))), both.rejections());
        assertEquals(Optional.of("no automatic transition was admitted: GO_FAST (GUARD fast): the fast lane is"
                + " closed; GO_SLOW (GUARD slow): the slow lane is closed"), both.explanation());
        assertEquals(LaneStatus.READY, lane.status());
    }

    /**
     * A lane whose holder was cancelled is detached on each candidate, before any guard runs: the advance goes on to
     * the next candidate, and lists every rejection.
     */
    @Test
    void aDetachedCandidatePassesToTheNext() {
        GuardProbe<Lane, LaneStatus, LaneTransition> fast = new GuardProbe<>();
        Lane lane = new Lane("l1", LaneStatus.READY);
        String reason = "no parent holds it in HOLDING";
        try (MachineRegistry registry = held(new Holder("h1", HolderStatus.CANCELLED, lane),
                lanes(fast, new GuardProbe<>(), new GuardProbe<>(), false))) {
            Outcome.Ignored<Lane, LaneStatus, LaneTransition> ignored =
                    registry.of(LANES).advance(lane).asIgnored().orElseThrow();
            assertEquals(List.of(
                    new Eligibility.Ineligible<>(lane, LaneStatus.READY, LaneTransition.GO_FAST, reason,
                            RejectionKind.DETACHED, Optional.empty()),
                    new Eligibility.Ineligible<>(lane, LaneStatus.READY, LaneTransition.GO_SLOW, reason,
                            RejectionKind.DETACHED, Optional.empty())), ignored.rejections());
            assertEquals(Optional.of("no automatic transition was admitted: GO_FAST (DETACHED): " + reason
                    + "; GO_SLOW (DETACHED): " + reason), ignored.explanation());
        }
        assertEquals(0, fast.calls);
        assertEquals(LaneStatus.READY, lane.status());
    }

    /**
     * Every state of the onboarding, with every guard failing and then with every guard passing: an advance ends
     * applied or ignored, never rejected.
     */
    @Test
    void advanceNeverReturnsRejected() {
        List<String> codes = new ArrayList<>();
        for (boolean complete : List.of(false, true)) {
            for (AccountStatus status : AccountStatus.values()) {
                Account account = account(status);
                account.setInfoComplete(complete);
                account.setAddressComplete(complete);
                account.setPremium(complete);
                codes.add(status + " " + accounts.advance(account).code());
            }
        }
        assertEquals(List.of("NEW NO_AUTOMATIC_TRANSITION", "PASSWORD_SET NO_CANDIDATE_ADMITTED",
                "INFO_SET NO_CANDIDATE_ADMITTED", "ADDRESS_SET APPLIED", "ACTIVE NO_AUTOMATIC_TRANSITION",
                "PREMIUM NO_AUTOMATIC_TRANSITION", "FLAGGED APPLIED", "REVIEW NO_AUTOMATIC_TRANSITION",
                "CLOSED NO_AUTOMATIC_TRANSITION",
                "NEW NO_AUTOMATIC_TRANSITION", "PASSWORD_SET APPLIED", "INFO_SET APPLIED", "ADDRESS_SET APPLIED",
                "ACTIVE NO_AUTOMATIC_TRANSITION", "PREMIUM NO_AUTOMATIC_TRANSITION", "FLAGGED APPLIED",
                "REVIEW NO_AUTOMATIC_TRANSITION", "CLOSED NO_AUTOMATIC_TRANSITION"), codes);
    }

    @Test
    void aRoutedFailureIsReturnedAsFailed() {
        verifier.behaviour = (Account target) ->
                ActionOutcome.failed(AddressFailures.Unverifiable.class, "address cannot be verified");
        Account account = complete(account(INFO_SET));
        Outcome.Failed<Account, AccountStatus, AccountTransition> failed =
                accounts.advance(account).asFailed().orElseThrow();
        assertEquals(COMPLETE_ADDRESS, failed.transition());
        assertEquals(ADDRESS_SET, failed.to());
        assertEquals(FLAG, failed.failureTransition());
        assertEquals(FLAGGED, failed.movedTo());
        assertEquals(Optional.of(AddressFailures.Unverifiable.class), failed.failureKind());
        assertEquals("address cannot be verified", failed.reason());
        assertEquals(FLAGGED, account.status());
    }

    /**
     * A failure without a route throws what execute throws for it, and so does an action that throws: the call is the
     * advance, which requests no identifier, and the entity stays where it was.
     */
    @Test
    void anUnhandledFailureThrowsLikeExecute() {
        verifier.behaviour = (Account target) -> ActionOutcome.failed("the verifier did not answer");
        UnhandledFailureException unhandled = assertThrows(UnhandledFailureException.class,
                () -> accounts.advance(complete(account(INFO_SET))));
        assertSameFailure(assertThrows(UnhandledFailureException.class,
                () -> accounts.execute(complete(account(INFO_SET)), COMPLETE_ADDRESS)), unhandled);
        verifier.behaviour = (Account target) -> {
            throw new IllegalStateException("verifier down");
        };
        Account account = complete(account(INFO_SET));
        TransitionAbortedException aborted =
                assertThrows(TransitionAbortedException.class, () -> accounts.advance(account));
        assertSameFailure(assertThrows(TransitionAbortedException.class,
                () -> accounts.execute(complete(account(INFO_SET)), COMPLETE_ADDRESS)), aborted);
        assertInstanceOf(IllegalStateException.class, aborted.getCause());
        assertEquals(CallRequest.ADVANCE, aborted.call().request());
        assertEquals(Optional.empty(), aborted.call().transition());
        assertEquals("Account/AccountStatus advance", aborted.call().toString());
        assertEquals(INFO_SET, account.status());
    }

    /**
     * A state that cannot be read, a getter that throws or answers null, ends the advance in RESOLVE before any
     * candidate, as it ends a reevaluation: the failure is the advance's own, and names no state and no identifier.
     */
    @Test
    void aStateThatCannotBeReadAbortsTheAdvanceInResolve() {
        GraphDefinition<Lane, LaneStatus, LaneTransition> graph =
                lanes(new GuardProbe<>(), new GuardProbe<>(), new GuardProbe<>(), false);
        StateMachine<Lane, LaneStatus, LaneTransition> throwing = StateMachines.from(graph)
                .state((Lane lane) -> {
                    throw new IllegalStateException("the lane cannot be read");
                }, Lane::setStatus)
                .build();
        StateMachine<Lane, LaneStatus, LaneTransition> unset =
                StateMachines.from(graph).state((Lane lane) -> null, Lane::setStatus).build();
        Lane lane = new Lane("l1", LaneStatus.READY);
        for (StateMachine<Lane, LaneStatus, LaneTransition> machine : List.of(throwing, unset)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> machine.advance(lane));
            assertEquals(List.of(CallPhase.RESOLVE, StatesWritten.NONE, CallRequest.ADVANCE),
                    List.of(error.phase(), error.statesWritten(), error.call().request()));
            assertEquals(List.of(Optional.empty(), Optional.empty()), List.of(error.from(), error.transition()));
        }
        assertInstanceOf(IllegalStateException.class,
                assertThrows(TransitionAbortedException.class, () -> throwing.advance(lane)).getCause());
        assertInstanceOf(UninitializedStateException.class,
                assertThrows(TransitionAbortedException.class, () -> unset.advance(lane)).getCause());
    }

    /** Each advance is one call to the observers, named as an advance, whether it ran an edge or ran none. */
    @Test
    void anAdvanceIsObservedAsOneCall() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(lanes(new GuardProbe<>(), new GuardProbe<>(), new GuardProbe<>(), false))
                        .state(Lane::status, Lane::setStatus).identity(Lane::id))
                .observer(recorder)
                .build()) {
            registry.of(LANES).advance(new Lane("l1", LaneStatus.WAITING));
            registry.of(LANES).advance(new Lane("l2", LaneStatus.READY));
        }
        assertEquals(List.of("started Lane/LaneStatus advance", "completed Lane/LaneStatus advance IGNORED",
                "started Lane/LaneStatus advance", "completed Lane/LaneStatus advance APPLIED"), recorder.events);
    }

    /** {@code advanced} failed as {@code executed} did: same class, phase, states written and edge. */
    private static void assertSameFailure(TransitionAbortedException executed, TransitionAbortedException advanced) {
        assertEquals(executed.getClass(), advanced.getClass());
        assertEquals(executed.phase(), advanced.phase());
        assertEquals(executed.statesWritten(), advanced.statesWritten());
        assertEquals(List.of(executed.from(), executed.transition(), executed.to()),
                List.of(advanced.from(), advanced.transition(), advanced.to()));
    }

    /** A guard that throws fails the attempt in GUARD, as in execute, and the next candidate is not tried. */
    @Test
    void aGuardThatThrowsFailsTheAttempt() {
        GuardProbe<Lane, LaneStatus, LaneTransition> fast = new GuardProbe<>();
        fast.verdict = (GuardContext<Lane, LaneStatus, LaneTransition> ctx) -> {
            throw new IllegalStateException("the fast lane cannot tell");
        };
        GuardProbe<Lane, LaneStatus, LaneTransition> slow = new GuardProbe<>();
        Lane lane = new Lane("l1", LaneStatus.READY);
        StateMachine<Lane, LaneStatus, LaneTransition> machine = lanes(fast, slow);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.advance(lane));
        assertEquals(CallPhase.GUARD, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(Optional.of(LaneTransition.GO_FAST), error.transition());
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals(0, slow.calls);
        assertEquals(LaneStatus.READY, lane.status());
    }

    /**
     * A guard and an action that call advance are refused with the very exception reevaluate gets there, and the
     * entity they asked about does not move.
     */
    @Test
    void advanceWhereNoCallMayStartIsRefused() {
        List<String> refusals = new ArrayList<>();
        Account other = account(PASSWORD_SET);
        other.setInfoComplete(true);
        Lane waiting = new Lane("l2", LaneStatus.READY);
        GuardProbe<Lane, LaneStatus, LaneTransition> slow = new GuardProbe<>();
        StateMachine<Lane, LaneStatus, LaneTransition> machine = lanes(rejecting("the fast lane is closed"), slow);
        slow.verdict = (GuardContext<Lane, LaneStatus, LaneTransition> ctx) -> {
            refusals.add("guard advance " + refusal(() -> machine.advance(waiting)));
            refusals.add("guard reevaluate " + refusal(() -> machine.reevaluate(waiting)));
            return GuardResult.pass();
        };
        verifier.behaviour = (Account target) -> {
            refusals.add("action advance " + refusal(() -> accounts.advance(other)));
            refusals.add("action reevaluate " + refusal(() -> accounts.reevaluate(other)));
            return ActionOutcome.ok();
        };
        assertEquals(OutcomeCode.APPLIED, machine.advance(new Lane("l1", LaneStatus.READY)).code());
        assertEquals(OutcomeCode.APPLIED, accounts.advance(complete(account(INFO_SET))).code());
        assertEquals(List.of("guard advance ReentrantTransitionException",
                "guard reevaluate ReentrantTransitionException", "action advance ReentrantTransitionException",
                "action reevaluate ReentrantTransitionException"), refusals);
        assertEquals(List.of(PASSWORD_SET, LaneStatus.READY), List.of(other.status(), waiting.status()));
    }

    /** The simple name of the class of what {@code call} throws, or {@code none}. */
    private static String refusal(Runnable call) {
        try {
            call.run();
            return "none";
        } catch (RuntimeException refused) {
            return refused.getClass().getSimpleName();
        }
    }

    @Test
    void asyncAdvanceRunsOnTheExecutor() {
        List<String> threads = new ArrayList<>();
        verifier.behaviour = (Account target) -> {
            threads.add(Thread.currentThread().getName());
            return ActionOutcome.ok();
        };
        StateMachine<Account, AccountStatus, AccountTransition> machine = StateMachines.from(onboarding(verifier))
                .state(Account::status, Account::setStatus).identity(Account::id)
                .asyncExecutor((Runnable task) -> new Thread(task, "advancer").start())
                .build();
        Account account = complete(account(INFO_SET));
        Outcome<Account, AccountStatus, AccountTransition> outcome = machine.async().advance(account).join();
        assertEquals(Optional.of(COMPLETE_ADDRESS), outcome.requestedTransition());
        assertEquals(List.of("advancer"), threads);
        assertEquals(ADDRESS_SET, account.status());
    }

    /**
     * A new entity has committed nothing: in its initial state, its automatic edge runs when advance asks for it, and
     * a reevaluation does not run it.
     */
    @Test
    void aNewEntityAtInitialAdvancesOnlyWithAdvance() {
        StateMachine<Lane, LaneStatus, LaneTransition> machine =
                laneMachine(lanes(new GuardProbe<>(), new GuardProbe<>(), new GuardProbe<>(), true));
        Lane lane = new Lane("l1", LaneStatus.WAITING);
        assertEquals(IgnoreReason.NO_WATCH, machine.reevaluate(lane).asIgnored().orElseThrow().reason());
        assertEquals(LaneStatus.WAITING, lane.status());
        Outcome<Lane, LaneStatus, LaneTransition> outcome = machine.advance(lane);
        assertEquals(Optional.of(LaneTransition.PREPARE), outcome.requestedTransition());
        assertEquals(LaneStatus.READY, lane.status());
    }
}
