package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AccountGraphs.machine;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountStatus.FLAGGED;
import static io.github.graphmaquina.fixtures.AccountStatus.INFO_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_ADDRESS;
import static io.github.graphmaquina.fixtures.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.ACCOUNTS;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.StepTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressFailures;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What stops a chain of automatic attempts, and how: an attempt that fails, after or before it wrote, is an
 * {@code AutomaticStep.Aborted} that ends its chain, and never reaches the caller of the call that committed first; a
 * failure of that call itself, a hook that throws or a failure route that does not continue, runs no chain; an Error
 * propagates unchanged. The account fixture and a doc pipeline, whose hooks, setter and partner cascade the test
 * makes fail, show each case.
 */
class AutomaticChainFailureTest {

    private final AddressVerifier verifier = new AddressVerifier();
    private final StateMachine<Account, AccountStatus, AccountTransition> accounts = machine(onboarding(verifier));

    /** The hooks the pipeline ran, as {@code <hook>:<id>}, the one that threw included. */
    private final List<String> hooks = new ArrayList<>();
    /** The {@code <hook>:<id>} of the pipeline's hook that throws; none when empty. */
    private String failingHook = "";
    /** The id of the doc whose setter throws; none when empty. */
    private String failingSetter = "";
    /** The doc that PUBLISH of {@code d1} cascades SUBMIT into, if any. */
    private Optional<Doc> partner = Optional.empty();
    /** The doc that watches the pipeline from DRAFT, and discards itself once a doc reaches PUBLISHED. */
    private final Doc watcher = new Doc("watcher", DRAFT);

    /** An account in {@code status} whose personal info and address are complete. */
    private static Account complete(AccountStatus status) {
        Account account = new Account("a1", status);
        account.setInfoComplete(true);
        account.setAddressComplete(true);
        return account;
    }

    /**
     * The doc pipeline: DRAFT -SUBMIT-> REVIEW, manual, with the hook submit; REVIEW -PUBLISH-> PUBLISHED, automatic,
     * whose action cascades SUBMIT into the {@link #partner} of {@code d1}, with the hook publish; PUBLISHED
     * -ARCHIVE-> ARCHIVED, automatic; and DRAFT -DISCARD-> ARCHIVED, manual. The {@link #watcher} watches the docs
     * from DRAFT and discards itself once a doc moved to PUBLISHED.
     */
    private MachineRegistry pipeline() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a doc in review is published")
                .statePolicy(PUBLISHED, "a published doc is archived")
                .transition(SUBMIT, DRAFT, REVIEW).afterCommit("submit", (Doc doc) -> hook("submit", doc))
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).automatic()
                    .action("publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> publish(ctx))
                    .afterCommit("publish", (Doc doc) -> hook("publish", doc)).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).automatic().endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc doc) -> List.of(doc),
                        (Doc moved) -> moved.status() == PUBLISHED ? Optional.of(watcher) : Optional.<Doc>empty())
                    .when("published", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(DISCARD)
                    .endWatch()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, (Doc doc, DocStatus status) -> {
                    if (doc.id().equals(failingSetter)) {
                        throw new IllegalStateException("the store is down");
                    }
                    doc.setStatus(status);
                }).identity(Doc::id))
                .build();
    }

    private void hook(String name, Doc doc) {
        String ran = name + ":" + doc.id();
        hooks.add(ran);
        if (ran.equals(failingHook)) {
            throw new IllegalStateException("the index is down");
        }
    }

    private ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
        if (ctx.entity().id().equals("d1") && partner.isPresent()) {
            ctx.cascade(DOCS, partner.get(), SUBMIT);
        }
        return ActionOutcome.ok();
    }

    /** A failure that no route takes, in a step, ends the chain as Aborted: the call that committed returns. */
    @Test
    void anUnhandledFailureInAStepIsAbortedNotThrown() {
        verifier.behaviour = (Account target) -> ActionOutcome.failed("the verifier did not answer");
        Account account = complete(NEW);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.execute(account, SET_PASSWORD);
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Aborted UnhandledFailureException"),
                described(outcome.automaticSteps()));
        AutomaticStep.Aborted aborted = assertInstanceOf(AutomaticStep.Aborted.class, outcome.automaticSteps().get(1));
        assertEquals(ACCOUNTS, aborted.key());
        assertSame(account, aborted.entity());
        UnhandledFailureException cause = assertInstanceOf(UnhandledFailureException.class, aborted.cause());
        assertEquals(CallOrigin.AUTOMATIC_STEP, cause.call().origin());
        assertEquals(StatesWritten.NONE, cause.statesWritten());
        assertEquals(INFO_SET, account.status());
    }

    /**
     * A hook of a step that throws ends the chain with what the step wrote: the attempt dispatches no watch and runs
     * no chain, and no later attempt follows; without that throw, the same step makes the watcher react and the chain
     * go on.
     */
    @Test
    void aHookThatThrowsInAStepStopsTheChainWithStatesWritten() {
        failingHook = "publish:d1";
        Doc doc = new Doc("d1", DRAFT);
        Doc control = new Doc("d2", DRAFT);
        try (MachineRegistry docs = pipeline()) {
            Outcome<Doc, DocStatus, DocTransition> outcome = docs.of(DOCS).execute(doc, SUBMIT);
            assertEquals(List.of("Aborted AfterCommitException"), described(outcome.automaticSteps()));
            AutomaticStep.Aborted aborted = (AutomaticStep.Aborted) outcome.automaticSteps().get(0);
            AfterCommitException cause = assertInstanceOf(AfterCommitException.class, aborted.cause());
            assertEquals(List.of(StatesWritten.ALL, "publish"), List.of(cause.statesWritten(), cause.hook()));
            assertEquals(List.of(PUBLISHED, DRAFT), List.of(doc.status(), watcher.status()));
            assertEquals(List.of(), outcome.reactions());
            Outcome<Doc, DocStatus, DocTransition> controlled = docs.of(DOCS).execute(control, SUBMIT);
            assertEquals(List.of("Done APPLIED PUBLISH PUBLISHED", "Done APPLIED ARCHIVE ARCHIVED"),
                    described(controlled.automaticSteps()));
        }
        assertEquals(List.of("submit:d1", "publish:d1", "submit:d2", "publish:d2"), hooks);
        assertEquals(List.of(ARCHIVED, ARCHIVED), List.of(control.status(), watcher.status()));
    }

    /** A hook of the application's own call that throws throws to it, as before: the commit chains nothing. */
    @Test
    void aHookThatThrowsInTheApplicationCallRunsNoChain() {
        failingHook = "submit:d1";
        Doc doc = new Doc("d1", DRAFT);
        try (MachineRegistry docs = pipeline()) {
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> docs.of(DOCS).execute(doc, SUBMIT));
            assertEquals("submit", error.hook());
        }
        assertEquals(List.of("submit:d1"), hooks);
        assertEquals(REVIEW, doc.status());
    }

    /** A setter that throws once a step wrote another entity ends the chain with the states partially written. */
    @Test
    void aSetterThatFailsMidwayIsAbortedWithPartialStates() {
        Doc other = new Doc("p1", DRAFT);
        partner = Optional.of(other);
        failingSetter = "p1";
        Doc doc = new Doc("d1", DRAFT);
        try (MachineRegistry docs = pipeline()) {
            Outcome<Doc, DocStatus, DocTransition> outcome = docs.of(DOCS).execute(doc, SUBMIT);
            assertEquals(List.of("Aborted CommitFailedException"), described(outcome.automaticSteps()));
            CommitFailedException cause = assertInstanceOf(CommitFailedException.class,
                    ((AutomaticStep.Aborted) outcome.automaticSteps().get(0)).cause());
            assertEquals(StatesWritten.PARTIAL, cause.statesWritten());
        }
        assertEquals(List.of(PUBLISHED, DRAFT), List.of(doc.status(), other.status()));
        assertEquals(List.of("submit:d1"), hooks);
    }

    /** A call of the application whose failure takes a route that does not continue chains nothing. */
    @Test
    void anApplicationCallFailedByAStoppingRouteRunsNoChain() {
        verifier.behaviour = (Account target) ->
                ActionOutcome.failed(AddressFailures.Unverifiable.class, "address cannot be verified");
        Account account = complete(INFO_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.execute(account, COMPLETE_ADDRESS);
        assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
        assertEquals(List.of(), outcome.automaticSteps());
        assertEquals(FLAGGED, account.status());
    }

    /**
     * A guard that throws on the second candidate of a lane aborts the step as it aborts an execute, in GUARD: the
     * lane stays where it was and the chain stops.
     */
    @Test
    void aGuardThatThrowsOnTheSecondCandidateAbortsTheStep() {
        GuardProbe<AdvanceTest.Lane, AdvanceTest.LaneStatus, AdvanceTest.LaneTransition> fast = new GuardProbe<>();
        fast.verdict = (GuardContext<AdvanceTest.Lane, AdvanceTest.LaneStatus, AdvanceTest.LaneTransition> ctx) ->
                GuardResult.reject("the fast lane is closed");
        GuardProbe<AdvanceTest.Lane, AdvanceTest.LaneStatus, AdvanceTest.LaneTransition> slow = new GuardProbe<>();
        slow.verdict = (GuardContext<AdvanceTest.Lane, AdvanceTest.LaneStatus, AdvanceTest.LaneTransition> ctx) -> {
            throw new IllegalStateException("the slow lane cannot tell");
        };
        AdvanceTest.Lane lane = new AdvanceTest.Lane("l1", AdvanceTest.LaneStatus.WAITING);
        Outcome<AdvanceTest.Lane, AdvanceTest.LaneStatus, AdvanceTest.LaneTransition> outcome =
                AdvanceTest.lanes(fast, slow).execute(lane, AdvanceTest.LaneTransition.PREPARE);
        assertEquals(List.of("Aborted TransitionAbortedException"), described(outcome.automaticSteps()));
        TransitionAbortedException cause = assertInstanceOf(TransitionAbortedException.class,
                ((AutomaticStep.Aborted) outcome.automaticSteps().get(0)).cause());
        assertEquals(List.of(CallPhase.GUARD, Optional.of(AdvanceTest.LaneTransition.GO_SLOW)),
                List.of(cause.phase(), cause.transition()));
        assertInstanceOf(IllegalStateException.class, cause.getCause());
        assertEquals(List.of(1, 1), List.of(fast.calls, slow.calls));
        assertEquals(AdvanceTest.LaneStatus.READY, lane.status());
    }

    /**
     * An Error in a step propagates unchanged to the caller of the call whose commit chained it; the observers hear
     * that call fail in AUTOMATIC_STEPS, with every state written.
     */
    @Test
    void anErrorPropagatesUnchanged() {
        AssertionError crash = new AssertionError("the verifier crashed");
        verifier.behaviour = (Account target) -> {
            throw crash;
        };
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        Account account = complete(NEW);
        try (MachineRegistry registry = AutomaticChainTest.observed(verifier, recorder)) {
            assertSame(crash, assertThrows(AssertionError.class,
                    () -> registry.of(ACCOUNTS).execute(account, SET_PASSWORD)));
        }
        assertEquals(List.of("started Account/AccountStatus SET_PASSWORD",
                "started Account/AccountStatus advance (automatic step)",
                "completed Account/AccountStatus advance (automatic step) APPLIED",
                "started Account/AccountStatus advance (automatic step)",
                "failed Account/AccountStatus advance (automatic step) ACTION NONE AssertionError",
                "failed Account/AccountStatus SET_PASSWORD AUTOMATIC_STEPS ALL AssertionError"), recorder.events);
        assertEquals(INFO_SET, account.status());
    }
}
