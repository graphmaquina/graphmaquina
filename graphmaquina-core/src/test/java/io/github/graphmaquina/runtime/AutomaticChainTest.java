package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AccountGraphs.machine;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboardingContinuing;
import static io.github.graphmaquina.fixtures.AccountStatus.ACTIVE;
import static io.github.graphmaquina.fixtures.AccountStatus.FLAGGED;
import static io.github.graphmaquina.fixtures.AccountStatus.INFO_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.REVIEW;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_INFO;
import static io.github.graphmaquina.fixtures.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.fixtures.AccountTransition.TOUCH;
import static io.github.graphmaquina.fixtures.Keys.ACCOUNTS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static io.github.graphmaquina.fixtures.StepTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressFailures;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * After a root call commits, its hooks run and its watch reactions, then each entity the commit moved runs its chain:
 * automatic attempts, each a call of its own, from the state the commit left it in, until an attempt admits no
 * candidate, the state has no automatic edge, or a failure route that does not continue is taken. The outcome of the
 * call lists those attempts in {@code automaticSteps()}. The onboarding of the account fixture shows each stop.
 */
class AutomaticChainTest {

    /** States of a parcel: once labelled, it awaits its receipt, then it is released and shipped. */
    enum ParcelStatus { PACKED, LABELLED, AWAITING, RELEASED, SHIPPED }

    /** Identifiers of the parcel machine. */
    enum ParcelTransition { LABEL, AWAIT, RELEASE, SHIP }

    /** The only state of a receipt, its initial and terminal state. */
    enum ReceiptStatus { SENT }

    /** Identifiers of the receipt machine; its graph declares no edge. */
    enum ReceiptTransition { RESEND }

    /** States of a task: a finished task is done, and a blocked one is escalated. */
    enum TaskStatus { OPEN, DONE, BLOCKED, ESCALATED }

    /** Identifiers of the task machine. */
    enum TaskTransition { FINISH, BLOCK, ESCALATE }

    static final MachineKey<Task, TaskStatus, TaskTransition> TASKS =
            MachineKey.of(Task.class, TaskStatus.class, TaskTransition.class);

    /** A task: an id and its status. */
    @Getter(AccessLevel.PACKAGE)
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Task {

        private final String id;
        private TaskStatus status = TaskStatus.OPEN;

        void setStatus(TaskStatus status) { this.status = status; }
    }

    static final MachineKey<Parcel, ParcelStatus, ParcelTransition> PARCELS =
            MachineKey.of(Parcel.class, ParcelStatus.class, ParcelTransition.class);

    static final MachineKey<Parcel, ReceiptStatus, ReceiptTransition> RECEIPTS =
            MachineKey.of(Parcel.class, ReceiptStatus.class, ReceiptTransition.class);

    /** A parcel and its receipt, two states of one entity. */
    @Getter(AccessLevel.PACKAGE)
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Parcel {

        private final String id;
        private ParcelStatus status = ParcelStatus.PACKED;
        private ReceiptStatus receipt = ReceiptStatus.SENT;

        void setStatus(ParcelStatus status) { this.status = status; }

        void setReceipt(ReceiptStatus receipt) { this.receipt = receipt; }
    }

    /** What the probes of a task machine counted while {@link #armed} was true. */
    static final class Calls {

        boolean armed;
        int identity;
        int state;
    }

    /**
     * The identity of a task: its id, compared by value, whose hashCode and equals count each call in {@code calls}
     * while it is armed.
     *
     * @param id the task's id
     * @param calls where the calls are counted
     */
    record CountedId(String id, Calls calls) {

        @Override
        public boolean equals(Object other) {
            calls.identity += calls.armed ? 1 : 0;
            return other instanceof CountedId that && that.id.equals(id);
        }

        @Override
        public int hashCode() {
            calls.identity += calls.armed ? 1 : 0;
            return id.hashCode();
        }
    }

    private final AddressVerifier verifier = new AddressVerifier();
    private final StateMachine<Account, AccountStatus, AccountTransition> accounts = machine(onboarding(verifier));
    /** The after-commit hook of FINISH in {@link #counted}. */
    private Consumer<Task> onFinish = (Task task) -> { };

    /** An account in {@code status} whose personal info is complete and whose address is complete or not. */
    private static Account account(AccountStatus status, boolean addressComplete) {
        Account account = new Account("a1", status);
        account.setInfoComplete(true);
        account.setAddressComplete(addressComplete);
        return account;
    }

    /** A registry of the onboarding machine of {@code verifier}, observed by {@code recorder}. */
    static MachineRegistry observed(AddressVerifier verifier, ObserverTest.Recorder recorder) {
        return MachineRegistry.builder()
                .add(StateMachines.from(onboarding(verifier)).state(Account::status, Account::setStatus)
                        .identity(Account::id))
                .observer(recorder)
                .build();
    }

    /** The attempt that no candidate admitted is listed too, with its rejections, and the chain stops there. */
    @Test
    void setPasswordChainsUntilAGuardRejects() {
        Account account = account(NEW, false);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.execute(account, SET_PASSWORD);
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(PASSWORD_SET, outcome.recordedState());
        assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Done NO_CANDIDATE_ADMITTED INFO_SET"),
                described(outcome.automaticSteps()));
        AutomaticStep.Done blocked = assertInstanceOf(AutomaticStep.Done.class, outcome.automaticSteps().get(1));
        assertEquals(ACCOUNTS, blocked.key());
        assertSame(account, blocked.entity());
        assertEquals(1, blocked.outcome().asIgnored().orElseThrow().rejections().size());
        assertEquals(INFO_SET, account.status());
        assertEquals(0, verifier.calls);
    }

    /** A chain that reaches a state without automatic edges makes no further attempt, so no Ignored ends its list. */
    @Test
    void aChainEndingInAStateWithoutAutomaticEdgesListsNoIgnored() {
        Account account = account(NEW, true);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.execute(account, SET_PASSWORD);
        assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Done APPLIED COMPLETE_ADDRESS ADDRESS_SET",
                "Done APPLIED ACTIVATE ACTIVE"), described(outcome.automaticSteps()));
        assertEquals(ACTIVE, account.status());
        assertEquals(1, verifier.calls);
    }

    /** A self-loop commits a hop, so the entity runs its chain even though its state did not change. */
    @Test
    void aSelfLoopTriggersTheChain() {
        Account account = account(PASSWORD_SET, false);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.execute(account, TOUCH);
        assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Done NO_CANDIDATE_ADMITTED INFO_SET"),
                described(outcome.automaticSteps()));
        assertEquals(INFO_SET, account.status());
    }

    /** The first attempt of an advance is the call itself; the attempts its commit chains are its steps. */
    @Test
    void advanceChainsAfterItsFirstAttempt() {
        Account account = account(PASSWORD_SET, true);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.advance(account);
        assertEquals(Optional.of(COMPLETE_INFO), outcome.requestedTransition());
        assertEquals(INFO_SET, outcome.recordedState());
        assertEquals(List.of("Done APPLIED COMPLETE_ADDRESS ADDRESS_SET", "Done APPLIED ACTIVATE ACTIVE"),
                described(outcome.automaticSteps()));
        assertEquals(ACTIVE, account.status());
    }

    /**
     * An attempt whose failure took a route that does not continue stops the chain in the recovery state, although
     * that state has an automatic edge of its own.
     */
    @Test
    void aRoutedFailureCommitStartsNoChainForThatEntity() {
        verifier.behaviour = (Account target) ->
                ActionOutcome.failed(AddressFailures.Unverifiable.class, "address cannot be verified");
        Account account = account(NEW, true);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.execute(account, SET_PASSWORD);
        assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Done FAILURE_ROUTED COMPLETE_ADDRESS FLAGGED"),
                described(outcome.automaticSteps()));
        assertEquals(FLAGGED, account.status());
    }

    /** A route that continues lets the chain go on from the recovery state. */
    @Test
    void aContinuingRouteChainsFromTheRecoveryState() {
        AddressVerifier unverifiable = new AddressVerifier();
        unverifiable.behaviour = (Account target) ->
                ActionOutcome.failed(AddressFailures.Unverifiable.class, "address cannot be verified");
        Account account = account(NEW, true);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingContinuing(unverifiable)).execute(account, SET_PASSWORD);
        assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Done FAILURE_ROUTED COMPLETE_ADDRESS FLAGGED",
                "Done APPLIED REQUEST_REVIEW REVIEW"), described(outcome.automaticSteps()));
        assertEquals(REVIEW, account.status());
    }

    /**
     * A call of the application that fails by a route that continues runs its entity's chain from the recovery state,
     * as an attempt does: here an advance, whose own attempt is that call.
     */
    @Test
    void anApplicationCallFailedByAContinuingRouteChainsFromTheRecoveryState() {
        AddressVerifier unverifiable = new AddressVerifier();
        unverifiable.behaviour = (Account target) ->
                ActionOutcome.failed(AddressFailures.Unverifiable.class, "address cannot be verified");
        Account account = account(INFO_SET, true);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingContinuing(unverifiable)).advance(account);
        assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
        assertEquals(FLAGGED, outcome.recordedState());
        assertEquals(List.of("Done APPLIED REQUEST_REVIEW REVIEW"), described(outcome.automaticSteps()));
        assertEquals(REVIEW, account.status());
    }

    /**
     * A route that continues may be declared on a manual edge too: the application's FINISH, which is not automatic,
     * fails into BLOCKED by its continuing onFailure, and the task's chain goes on from there through ESCALATE.
     */
    @Test
    void aManualEdgeFailedByAContinuingRouteChainsFromTheRecoveryState() {
        GraphDefinition<Task, TaskStatus, TaskTransition> tasks = StateGraph.define(TASKS)
                .initial(TaskStatus.OPEN).terminal(TaskStatus.DONE).terminal(TaskStatus.ESCALATED)
                .statePolicy(TaskStatus.OPEN, "an open task is finished")
                .statePolicy(TaskStatus.BLOCKED, "a blocked task is escalated")
                .transition(TaskTransition.FINISH, TaskStatus.OPEN, TaskStatus.DONE)
                    .action("finish", (TransitionContext<Task, TaskStatus, TaskTransition> ctx) ->
                            ActionOutcome.failed("the task is blocked"))
                    .onFailureAndContinue(TaskTransition.BLOCK)
                    .endTransition()
                .transition(TaskTransition.BLOCK, TaskStatus.OPEN, TaskStatus.BLOCKED).endTransition()
                .transition(TaskTransition.ESCALATE, TaskStatus.BLOCKED, TaskStatus.ESCALATED).automatic()
                    .endTransition()
                .build();
        Task task = new Task("t1");
        Outcome<Task, TaskStatus, TaskTransition> outcome = StateMachines.from(tasks)
                .state(Task::status, Task::setStatus).identity(Task::id).build()
                .execute(task, TaskTransition.FINISH);
        assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
        assertEquals(TaskStatus.BLOCKED, outcome.recordedState());
        assertEquals(List.of("Done APPLIED ESCALATE ESCALATED"), described(outcome.automaticSteps()));
        assertEquals(TaskStatus.ESCALATED, task.status());
    }

    /**
     * The task machine whose FINISH, OPEN to DONE, runs {@link #onFinish} after its commit, BLOCK leads to BLOCKED and
     * the automatic ESCALATE on to ESCALATED; its state getter and its identity count their calls in {@code calls}.
     */
    private StateMachine<Task, TaskStatus, TaskTransition> counted(Calls calls) {
        GraphDefinition<Task, TaskStatus, TaskTransition> tasks = StateGraph.define(TASKS)
                .initial(TaskStatus.OPEN).terminal(TaskStatus.DONE).terminal(TaskStatus.ESCALATED)
                .statePolicy(TaskStatus.OPEN, "an open task is finished")
                .statePolicy(TaskStatus.BLOCKED, "a blocked task is escalated")
                .transition(TaskTransition.FINISH, TaskStatus.OPEN, TaskStatus.DONE)
                    .afterCommit("finished", (Task task) -> onFinish.accept(task)).endTransition()
                .transition(TaskTransition.BLOCK, TaskStatus.OPEN, TaskStatus.BLOCKED).endTransition()
                .transition(TaskTransition.ESCALATE, TaskStatus.BLOCKED, TaskStatus.ESCALATED).automatic()
                    .endTransition()
                .build();
        return StateMachines.from(tasks)
                .state((Task task) -> {
                    calls.state += calls.armed ? 1 : 0;
                    return task.status();
                }, Task::setStatus)
                .identity((Task task) -> new CountedId(task.id(), calls))
                .build();
    }

    /**
     * The hook of the first task's FINISH finishes a second task, a call nested in the first commit's post-commit,
     * which reports the second task to it. The first task's chain then starts from DONE, which has no automatic edge:
     * it reads no state and runs no identity's hashCode or equals, which comparing it with the reported task would.
     */
    @Test
    void aStateWithoutAutomaticEdgesComparesNoIdentity() {
        Calls calls = new Calls();
        StateMachine<Task, TaskStatus, TaskTransition> tasks = counted(calls);
        Task first = new Task("t1");
        Task second = new Task("t2");
        onFinish = (Task task) -> {
            if (task == first) {
                tasks.execute(second, TaskTransition.FINISH);
                calls.armed = true;
            }
        };
        Outcome<Task, TaskStatus, TaskTransition> outcome = tasks.execute(first, TaskTransition.FINISH);
        calls.armed = false;
        assertEquals(List.of(OutcomeCode.APPLIED, List.of()), List.of(outcome.code(), outcome.automaticSteps()));
        assertEquals(List.of(TaskStatus.DONE, TaskStatus.DONE), List.of(first.status(), second.status()));
        assertEquals(List.of(0, 0), List.of(calls.identity, calls.state));
    }

    /**
     * An attempt whose own step enters a bound state whose child starts in a terminal state also runs that child's
     * exit: the chain goes on from where the exit left the entity, not from the destination of the edge the attempt
     * ran, which its outcome records.
     */
    @Test
    void aChainGoesOnFromWhereTheAttemptsOwnExitLeftTheEntity() {
        GraphDefinition<Parcel, ReceiptStatus, ReceiptTransition> receipts = StateGraph.define(RECEIPTS)
                .initial(ReceiptStatus.SENT).terminal(ReceiptStatus.SENT).build();
        GraphDefinition<Parcel, ParcelStatus, ParcelTransition> parcels = StateGraph.define(PARCELS)
                .initial(ParcelStatus.PACKED).terminal(ParcelStatus.SHIPPED)
                .statePolicy(ParcelStatus.PACKED, "a parcel is labelled")
                .statePolicy(ParcelStatus.LABELLED, "a labelled parcel awaits its receipt")
                .statePolicy(ParcelStatus.AWAITING, "a parcel is released once its receipt is sent")
                .statePolicy(ParcelStatus.RELEASED, "a released parcel is shipped")
                .transition(ParcelTransition.LABEL, ParcelStatus.PACKED, ParcelStatus.LABELLED).endTransition()
                .transition(ParcelTransition.AWAIT, ParcelStatus.LABELLED, ParcelStatus.AWAITING).automatic()
                    .endTransition()
                .transition(ParcelTransition.RELEASE, ParcelStatus.AWAITING, ParcelStatus.RELEASED).endTransition()
                .transition(ParcelTransition.SHIP, ParcelStatus.RELEASED, ParcelStatus.SHIPPED).automatic()
                    .endTransition()
                .submachine(ParcelStatus.AWAITING, receipts).exit(ReceiptStatus.SENT, ParcelTransition.RELEASE)
                    .endSubmachine()
                .build();
        Parcel parcel = new Parcel("p1");
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(parcels).state(Parcel::status, Parcel::setStatus).identity(Parcel::id))
                .add(StateMachines.from(receipts).state(Parcel::receipt, Parcel::setReceipt).identity(Parcel::id))
                .build()) {
            Outcome<Parcel, ParcelStatus, ParcelTransition> outcome =
                    registry.of(PARCELS).execute(parcel, ParcelTransition.LABEL);
            assertEquals(List.of("Done APPLIED AWAIT AWAITING", "Done APPLIED SHIP SHIPPED"),
                    described(outcome.automaticSteps()));
            assertEquals(2, ((AutomaticStep.Done) outcome.automaticSteps().get(0)).outcome().records().size());
        }
        assertEquals(ParcelStatus.SHIPPED, parcel.status());
    }

    /**
     * Each attempt is a call of its own, an advance made by an automatic step, which starts and ends inside the call
     * whose commit triggered it, after that call's commit and before it completes.
     */
    @Test
    void eachAttemptIsObservedAsItsOwnCall() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        try (MachineRegistry registry = observed(verifier, recorder)) {
            registry.of(ACCOUNTS).execute(account(NEW, false), SET_PASSWORD);
        }
        assertEquals(List.of("started Account/AccountStatus SET_PASSWORD",
                "started Account/AccountStatus advance (automatic step)",
                "completed Account/AccountStatus advance (automatic step) APPLIED",
                "started Account/AccountStatus advance (automatic step)",
                "completed Account/AccountStatus advance (automatic step) IGNORED",
                "completed Account/AccountStatus SET_PASSWORD APPLIED"), recorder.events);
        MachineCall attempt = recorder.calls.get(1);
        assertEquals(List.of(CallRequest.ADVANCE, CallOrigin.AUTOMATIC_STEP, Optional.empty()),
                List.of(attempt.request(), attempt.origin(), attempt.transition()));
    }

    /**
     * Each attempt logs as an advance whose start line names the call whose commit triggered it, and the end line of
     * that call counts its automatic steps.
     */
    @Test
    void attemptsLogAdvanceWithTrigger() {
        try (LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            accounts.execute(account(NEW, false), SET_PASSWORD);
            assertEquals(List.of(
                    "FINE call=A start Account/AccountStatus SET_PASSWORD",
                    "FINE call=A committed Account/AccountStatus#a1 NEW -SET_PASSWORD-> PASSWORD_SET depth=0",
                    "FINE call=B start Account/AccountStatus advance trigger=A",
                    "FINE call=B committed Account/AccountStatus#a1 PASSWORD_SET -COMPLETE_INFO-> INFO_SET depth=0",
                    "FINE call=B end code=APPLIED state=INFO_SET entity=Account/AccountStatus#a1 records=1 starts=0"
                            + " reactions=0 aborted=0 automatic=0 elapsed=<us>",
                    "FINE call=C start Account/AccountStatus advance trigger=A",
                    "FINE call=C end code=NO_CANDIDATE_ADMITTED state=INFO_SET entity=Account/AccountStatus#a1"
                            + " records=0 starts=0 reactions=0 aborted=0 automatic=0 reason=\"no automatic transition"
                            + " was admitted: COMPLETE_ADDRESS (GUARD addressComplete): address is incomplete\""
                            + " elapsed=<us>",
                    "FINE call=A end code=APPLIED state=PASSWORD_SET entity=Account/AccountStatus#a1 records=1"
                            + " starts=0 reactions=0 aborted=0 automatic=2 elapsed=<us>"), lines(log));
        }
    }
}
