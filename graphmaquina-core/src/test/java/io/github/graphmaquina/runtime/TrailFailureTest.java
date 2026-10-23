package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.TrailFixtures.hopOf;
import static io.github.graphmaquina.fixtures.TrailFixtures.recording;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Fatal;
import io.github.graphmaquina.fixtures.TickingClock;
import io.github.graphmaquina.internal.FrozenFlow;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Spec R8, R15, R16.2 and D11: the trail of a call that does not complete. A rejected call ends with its
 * {@code Rejected} entry, a failed one keeps what it took before it failed and then holds a {@code Failed} entry, and
 * so does a reevaluation that fails before it runs an edge; a rollback drops the entries of what it undoes. A clock
 * that throws or answers null fails the call, whichever entry it could not mark, and an Error it throws propagates as
 * itself.
 */
class TrailFailureTest {

    /** An observer that keeps every failure it is told of. */
    private static MachineObserver failures(List<CallFailure> failures) {
        return new MachineObserver() {
            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                failures.add(failure);
            }
        };
    }

    /**
     * The engine rolls the step of an UnhandledFailureException back before it finds that no route takes the failure,
     * so the trail lacks the entries of that step: at the root, the trail is the {@code Failed} entry alone; for a
     * cascaded step, the parent's hop, then {@code Failed}.
     */
    @Test
    void anUnhandledFailureLeavesTheEntriesOfItsStepOutOfTheTrail() {
        Doc root = new Doc("1", DRAFT);
        Doc parent = new Doc("2", DRAFT);
        Doc child = new Doc("3", DRAFT);
        TickingClock clock = new TickingClock();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.failed("no reviewer")),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        }));
        UnhandledFailureException alone;
        UnhandledFailureException nested;
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(graph, clock)).build()) {
            alone = assertThrows(UnhandledFailureException.class, () -> registry.of(DOCS).execute(root, SUBMIT));
            nested = assertThrows(UnhandledFailureException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of(new FlowEntry.Failed(new CallFailure(CallPhase.ACTION, StatesWritten.NONE, alone),
                TickingClock.at(2))), alone.flow().trail());
        List<FlowEntry> trail = nested.flow().trail();
        assertEquals(2, trail.size());
        assertSame(parent, hopOf(trail.get(0)).entity());
        assertEquals(DISCARD, hopOf(trail.get(0)).transition());
        assertEquals(TickingClock.at(3), trail.get(0).at());
        assertEquals(new FlowEntry.Failed(new CallFailure(CallPhase.ACTION, StatesWritten.NONE, nested),
                TickingClock.at(5)), trail.get(1));
    }

    /**
     * Spec R16.2: a {@code COLLECT} element whose step aborts is rolled back with its entry and its mark, so the next
     * element's hop takes its place. The aggregate then aborts the call, whose failure keeps the hops taken and ends
     * with {@code Failed}.
     */
    @Test
    void aCollectElementThatAbortsLeavesNoEntry() {
        Doc parent = new Doc("1", DRAFT);
        Doc aborting = new Doc("2", DRAFT);
        Doc applied = new Doc("3", DRAFT);
        TickingClock clock = new TickingClock();
        List<CallFailure> failures = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == aborting) {
                                throw new IllegalStateException("reviewer unavailable");
                            }
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascadeAll(DOCS, List.of(aborting, applied), SUBMIT, CascadePolicy.COLLECT);
                            return ActionOutcome.ok();
                        }));
        CascadeException error;
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(graph, clock))
                .observer(failures(failures)).build()) {
            error = assertThrows(CascadeException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        List<FlowEntry> trail = error.flow().trail();
        assertEquals(3, trail.size());
        assertSame(parent, hopOf(trail.get(0)).entity());
        assertEquals(TickingClock.at(1), trail.get(0).at());
        assertSame(applied, hopOf(trail.get(1)).entity());
        assertEquals(TickingClock.at(3), trail.get(1).at());
        assertEquals(new FlowEntry.Failed(failures.get(0), TickingClock.at(4)), trail.get(2));
        assertSame(error, failures.get(0).error());
    }

    private static void assertTheHopsThenTheFailure(TransitionAbortedException aborted, CallFailure heard,
                                                    Doc parent, Doc child, IllegalStateException unavailable) {
        List<FlowEntry> trail = aborted.flow().trail();
        assertEquals(3, trail.size());
        assertSame(parent, hopOf(trail.get(0)).entity());
        assertSame(child, hopOf(trail.get(1)).entity());
        assertEquals(List.of(TickingClock.at(1), TickingClock.at(2)), List.of(trail.get(0).at(), trail.get(1).at()));
        FlowEntry.Failed failed = assertInstanceOf(FlowEntry.Failed.class, trail.get(2));
        assertEquals(heard, failed.failure());
        assertEquals(TickingClock.at(3), failed.at());
        assertSame(aborted, failed.failure().error());
        assertSame(unavailable, aborted.getCause());
        assertEquals(new CallFailure(CallPhase.ACTION, StatesWritten.NONE, aborted), failed.failure());
        assertTrue(trail.toString().contains("Failed[failure=CallFailure[phase=ACTION, statesWritten=NONE, error="
                + aborted + "]"), trail.toString());
    }

    /**
     * Spec R8 and R15.2: a failed call keeps the hops it took before it failed, then {@code Failed}, which holds a
     * {@code CallFailure} equal to the one the observers receive, read when the root caught the failure. Before the
     * commit nothing was written; a hook that throws after it ends the trail with a failure that wrote every state.
     * The trail prints without walking back into the failure's own flow.
     */
    @Test
    void aFailedCallKeepsWhatItTookThenItsFailure() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        Doc hooked = new Doc("3", DRAFT);
        IllegalStateException unavailable = new IllegalStateException("archive unavailable");
        IllegalStateException unreachable = new IllegalStateException("store unreachable");
        TickingClock clock = new TickingClock();
        List<CallFailure> failures = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            if (committed == hooked) {
                                throw unreachable;
                            }
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, child, SUBMIT);
                            throw unavailable;
                        }));
        TransitionAbortedException aborted;
        AfterCommitException afterCommit;
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(graph, clock))
                .observer(failures(failures)).build()) {
            aborted = assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
            afterCommit = assertThrows(AfterCommitException.class, () -> registry.of(DOCS).execute(hooked, SUBMIT));
        }
        assertTheHopsThenTheFailure(aborted, failures.get(0), parent, child, unavailable);

        List<FlowEntry> hooks = afterCommit.flow().trail();
        assertEquals(2, hooks.size());
        assertSame(hooked, hopOf(hooks.get(0)).entity());
        assertEquals(new FlowEntry.Failed(failures.get(1), TickingClock.at(5)), hooks.get(1));
        assertEquals(new CallFailure(CallPhase.HOOK, StatesWritten.ALL, afterCommit), failures.get(1));
        assertEquals(REVIEW, hooked.status());
    }

    /**
     * Spec R15.1 for a hook that throws: the call built the records and the starts of what it committed once, and its
     * {@code AfterCommitException} lists them; the hop and the start entries of its trail wrap those very instances.
     */
    @Test
    void aHookFailureTrailWrapsTheRecordsAndStartsItsFailureLists() {
        Billing billing = new Billing();
        billing.afterHook = (String hop) -> {
            throw new IllegalStateException("audit unreachable");
        };
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.AUTHORIZING));
        AfterCommitException failure;
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(billing.bookingMachine().recordTrail(new TickingClock())).add(billing.paymentMachine()).build()) {
            failure = assertThrows(AfterCommitException.class,
                    () -> registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK));
        }
        List<FlowEntry> trail = failure.flow().trail();
        assertEquals(3, trail.size());
        assertSame(failure.records().get(0), hopOf(trail.get(0)));
        assertSame(failure.starts().get(0), assertInstanceOf(FlowEntry.Start.class, trail.get(1)).start());
        assertInstanceOf(FlowEntry.Failed.class, trail.get(2));
    }

    /**
     * Spec R8.3: a rejected call ends with its rejection, read when the root built its outcome: the root's machine and
     * entity, the state, the identifier, the kind, the reason and the guard, if one rejected.
     */
    @Test
    void aRejectedCallEndsWithItsRejection() {
        Doc doc = new Doc("1", DRAFT);
        TickingClock clock = new TickingClock();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("nothing to review")));
        StateMachine<Doc, DocStatus, DocTransition> machine = recording(graph, clock).build();
        Outcome<Doc, DocStatus, DocTransition> guarded = machine.execute(doc, SUBMIT);
        Outcome<Doc, DocStatus, DocTransition> noEdge = machine.execute(doc, PUBLISH);
        assertEquals(new FrozenFlow(Optional.of(doc), Map.of(), List.of(new FlowEntry.Rejected(DOCS, doc, DRAFT,
                SUBMIT, RejectionKind.GUARD, "nothing to review", Optional.of("Ready"), TickingClock.at(1)))),
                guarded.flow());
        assertEquals(List.of(new FlowEntry.Rejected(DOCS, doc, DRAFT, PUBLISH, RejectionKind.NO_TRANSITION,
                "a draft is submitted or discarded", Optional.empty(), TickingClock.at(2))), noEdge.flow().trail());
        assertEquals(2, clock.reads());
    }

    /**
     * Spec D11: a reevaluation that fails before it runs an edge has no transaction, but its machine records its trail,
     * so its failure hands out the root entity, no value, and a single {@code Failed} entry with a
     * {@code CallFailure} equal to the one the observers receive: here, a state that cannot be read.
     */
    @Test
    void aReevaluationThatFailsBeforeItRunsAnEdgeEndsWithFailed() {
        Doc unreadable = new Doc("1", DRAFT);
        TickingClock clock = new TickingClock();
        List<CallFailure> failures = new ArrayList<>();
        TransitionAbortedException read;
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs(UnaryOperator.identity()))
                        .state((Doc doc) -> {
                            throw new IllegalStateException("unreadable");
                        }, Doc::setStatus)
                        .identity(Doc::id).recordTrail(clock))
                .observer(failures(failures)).build()) {
            read = assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).reevaluate(unreadable));
        }
        assertEquals(CallPhase.RESOLVE, read.phase());
        assertEquals(new FrozenFlow(Optional.of(unreadable), Map.of(),
                List.of(new FlowEntry.Failed(failures.get(0), TickingClock.at(1)))), read.flow());
        FlowEntry.Failed failed = assertInstanceOf(FlowEntry.Failed.class, read.flow().trail().get(0));
        assertEquals(failures.get(0), failed.failure());
        assertSame(read, failed.failure().error());
    }

    /**
     * Spec D11 for a watch that throws: a reevaluation whose watch condition throws fails before it runs an edge, and
     * its machine records its trail, so its failure hands out the root entity, no value, and a single {@code Failed}
     * entry equal to the observers' {@code CallFailure}, attached before the observers hear of the failure.
     */
    @Test
    void aReevaluationWhoseWatchThrowsEndsWithFailedBeforeTheObserversHear() {
        Doc doc = new Doc("1", DRAFT);
        TickingClock clock = new TickingClock();
        List<CallFailure> failures = new ArrayList<>();
        List<FlowView> heard = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> broken = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("broken", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        throw new IllegalStateException("broken condition");
                    }).execute(SUBMIT)
                    .endWatch()
                .build();
        MachineObserver reading = new MachineObserver() {
            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                failures.add(failure);
                heard.add(((CallFailedException) failure.error()).flow());
            }
        };
        TransitionAbortedException watch;
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(broken, clock)).observer(reading)
                .build()) {
            watch = assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).reevaluate(doc));
        }
        assertEquals(CallPhase.RESOLVE, watch.phase());
        assertEquals(new FrozenFlow(Optional.of(doc), Map.of(),
                List.of(new FlowEntry.Failed(failures.get(0), TickingClock.at(1)))), watch.flow());
        assertSame(watch, failures.get(0).error());
        assertEquals(List.of(watch.flow()), heard);
    }

    /**
     * A clock that throws fails the call, as an identity function that throws does: the root's own step aborts with
     * what it threw as cause, and a cascaded step poisons the call through an abort that carries it. The entry the
     * clock could not mark stays out of the trail. When the clock throws again for the final entry, the failure
     * carries that exception as suppressed and its trail ends without it. A rejection whose instant cannot be read
     * fails the call the same way, and so does a reevaluation that fails before it runs an edge. Every one of these
     * calls throws its own {@code CallFailedException}, never what the clock threw, a clock that throws while it
     * marks a sub-machine's start included. An Error the clock throws propagates as itself.
     */
    @Test
    void aClockThatThrowsFailsTheCall() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = graphRejectingDocFourAndCascadingOnDiscard();
        assertTheRootsOwnStepAbortsWithWhatTheClockThrew(graph);
        assertACascadedStepPoisonsTheCallThroughAnAbort(graph);
        assertARejectionWhoseInstantCannotBeReadFailsTheCall(graph);
        assertAReevaluationWhoseFailureCannotBeMarkedFailsTheCall(graph);
        assertAStartThatCannotBeMarkedFailsTheCall();
        assertAnErrorTheClockThrowsPropagatesAsItself(graph);
    }

    /** SUBMIT rejects the doc 4 and passes the others; DISCARD cascades SUBMIT into the doc 2. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> graphRejectingDocFourAndCascadingOnDiscard() {
        Doc child = new Doc("2", DRAFT);
        return docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> ctx.entity().id().equals("4")
                                ? GuardResult.reject("nothing to review") : GuardResult.pass()),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        }));
    }

    /** A clock that throws on every read. */
    private static TickingClock stopped() {
        TickingClock stopped = new TickingClock();
        stopped.onRead = () -> {
            throw new IllegalStateException("clock stopped");
        };
        return stopped;
    }

    private static void assertTheRootsOwnStepAbortsWithWhatTheClockThrew(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        AtomicInteger calls = new AtomicInteger();
        TickingClock once = new TickingClock();
        once.onRead = () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("clock stopped");
            }
        };
        Doc root = new Doc("1", DRAFT);
        TransitionAbortedException first = assertThrows(TransitionAbortedException.class,
                () -> recording(graph, once).build().execute(root, SUBMIT));
        assertEquals(CallPhase.ACTION, first.phase());
        assertEquals("clock stopped", first.getCause().getMessage());
        assertEquals(List.of(new FlowEntry.Failed(new CallFailure(CallPhase.ACTION, StatesWritten.NONE, first),
                TickingClock.at(1))), first.flow().trail());
    }

    private static void assertACascadedStepPoisonsTheCallThroughAnAbort(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        TickingClock stopping = new TickingClock();
        stopping.onRead = () -> {
            if (stopping.reads() >= 1) {
                throw new IllegalStateException("clock stopped");
            }
        };
        Doc parent = new Doc("3", DRAFT);
        TransitionAbortedException cascaded = assertThrows(TransitionAbortedException.class,
                () -> recording(graph, stopping).build().execute(parent, DISCARD));
        assertEquals("clock stopped", cascaded.getCause().getMessage());
        assertEquals(1, cascaded.flow().trail().size());
        assertSame(parent, hopOf(cascaded.flow().trail().get(0)).entity());
        assertEquals(1, cascaded.getSuppressed().length);
        assertEquals("clock stopped", cascaded.getSuppressed()[0].getMessage());
    }

    private static void assertARejectionWhoseInstantCannotBeReadFailsTheCall(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        TickingClock stopped = stopped();
        Doc rejected = new Doc("4", DRAFT);
        TransitionAbortedException refused = assertThrows(TransitionAbortedException.class,
                () -> recording(graph, stopped).build().execute(rejected, SUBMIT));
        assertEquals(CallPhase.GUARD, refused.phase());
        assertEquals("clock stopped", refused.getCause().getMessage());
        assertEquals(new FrozenFlow(Optional.of(rejected), Map.of(), List.of()), refused.flow());
        assertEquals(1, refused.getSuppressed().length);
    }

    private static void assertAReevaluationWhoseFailureCannotBeMarkedFailsTheCall(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        TickingClock stopped = stopped();
        Doc unreadable = new Doc("5", DRAFT);
        TransitionAbortedException read = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(graph)
                        .state((Doc doc) -> {
                            throw new IllegalStateException("unreadable");
                        }, Doc::setStatus)
                        .recordTrail(stopped).build().reevaluate(unreadable));
        assertEquals(new FrozenFlow(Optional.of(unreadable), Map.of(), List.of()), read.flow());
        assertEquals("clock stopped", read.getSuppressed()[0].getMessage());
    }

    private static void assertAStartThatCannotBeMarkedFailsTheCall() {
        Billing billing = new Billing();
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.AUTHORIZING));
        AtomicInteger starting = new AtomicInteger();
        TickingClock atTheStart = new TickingClock();
        atTheStart.onRead = () -> {
            if (starting.incrementAndGet() == 2) {
                throw new IllegalStateException("clock stopped");
            }
        };
        TransitionAbortedException entering;
        try (MachineRegistry registry = MachineRegistry.builder().add(billing.bookingMachine().recordTrail(atTheStart))
                .add(billing.paymentMachine()).build()) {
            entering = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK));
        }
        assertEquals("clock stopped", entering.getCause().getMessage());
        assertEquals(2, entering.flow().trail().size());
        assertSame(booking, hopOf(entering.flow().trail().get(0)).entity());
        assertEquals(TickingClock.at(2), assertInstanceOf(FlowEntry.Failed.class, entering.flow().trail().get(1)).at());
    }

    private static void assertAnErrorTheClockThrowsPropagatesAsItself(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        Fatal fatal = new Fatal("broken clock");
        TickingClock broken = new TickingClock();
        broken.onRead = () -> {
            throw fatal;
        };
        assertSame(fatal, assertThrows(Fatal.class,
                () -> recording(graph, broken).build().execute(new Doc("6", DRAFT), SUBMIT)));
    }

    /**
     * An Error the clock throws while the call reads the instant of its final {@code Failed} entry propagates as
     * itself, and the observers still hear how the call ended: of that Error, in the phase the call reached and with
     * what it wrote. So does a reevaluation that fails before it runs an edge.
     */
    @Test
    void anErrorTheClockThrowsForTheFailedEntryStillEndsTheCallForTheObservers() {
        Fatal fatal = new Fatal("broken clock");
        TickingClock secondRead = new TickingClock();
        secondRead.onRead = () -> {
            if (secondRead.reads() == 1) {
                throw fatal;
            }
        };
        TickingClock firstRead = new TickingClock();
        firstRead.onRead = () -> {
            throw fatal;
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            throw new IllegalStateException("store unreachable");
                        }));
        List<CallFailure> failures = new ArrayList<>();
        Doc hooked = new Doc("1", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(graph, secondRead))
                .observer(failures(failures)).build()) {
            assertSame(fatal, assertThrows(Fatal.class, () -> registry.of(DOCS).execute(hooked, SUBMIT)));
        }
        assertEquals(REVIEW, hooked.status());
        assertEquals(List.of(new CallFailure(CallPhase.HOOK, StatesWritten.ALL, fatal)), failures);

        failures.clear();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph)
                        .state((Doc doc) -> {
                            throw new IllegalStateException("unreadable");
                        }, Doc::setStatus)
                        .identity(Doc::id).recordTrail(firstRead))
                .observer(failures(failures)).build()) {
            assertSame(fatal, assertThrows(Fatal.class, () -> registry.of(DOCS).reevaluate(new Doc("2", DRAFT))));
        }
        assertEquals(List.of(new CallFailure(CallPhase.RESOLVE, StatesWritten.NONE, fatal)), failures);
    }

    /**
     * A clock that throws, for the final {@code Failed} entry, the very failure the call throws cannot leave it on
     * that failure as suppressed, since an exception cannot suppress itself: the call still throws its own failure,
     * with its flow, and the trail ends without the entry. Here an action catches the abort of its cascade, which
     * poisoned the call, and the clock throws that abort back once it has it.
     */
    @Test
    void aClockThatThrowsTheCallsOwnFailureLeavesTheFailedEntryOut() {
        Doc child = new Doc("2", DRAFT);
        List<TransitionAbortedException> caught = new ArrayList<>();
        TickingClock rethrowing = new TickingClock();
        rethrowing.onRead = () -> {
            if (!caught.isEmpty()) {
                throw caught.get(0);
            }
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("nothing to review")),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, child, SUBMIT);
                            } catch (TransitionAbortedException abort) {
                                caught.add(abort);
                            }
                            return ActionOutcome.ok();
                        }));
        Doc parent = new Doc("1", DRAFT);
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> recording(graph, rethrowing).build().execute(parent, DISCARD));
        assertSame(caught.get(0), thrown);
        assertEquals(0, thrown.getSuppressed().length);
        List<FlowEntry> trail = thrown.flow().trail();
        assertEquals(1, trail.size());
        assertSame(parent, hopOf(trail.get(0)).entity());
    }

    /**
     * A clock that answers null fails the call as one that throws does, with a NullPointerException that says so: the
     * hop it could not mark aborts the root's step before anything is written, and the rejection whose instant it
     * could not read fails the call. The trail ends without the entries the clock could not mark.
     */
    @Test
    void aClockThatAnswersNullFailsTheCallAsOneThatThrows() {
        InstantSource silent = () -> null;
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> ctx.entity().id().equals("2")
                                ? GuardResult.reject("nothing to review") : GuardResult.pass()));
        StateMachine<Doc, DocStatus, DocTransition> machine = recording(graph, silent).build();

        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException hop = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(doc, SUBMIT));
        assertEquals(DRAFT, doc.status());
        assertEquals(CallPhase.ACTION, hop.phase());
        assertEquals("the clock of the trail answered null",
                assertInstanceOf(NullPointerException.class, hop.getCause()).getMessage());
        assertEquals(new FrozenFlow(Optional.of(doc), Map.of(), List.of()), hop.flow());
        assertEquals("the clock of the trail answered null",
                assertInstanceOf(NullPointerException.class, hop.getSuppressed()[0]).getMessage());

        Doc rejected = new Doc("2", DRAFT);
        TransitionAbortedException refused = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(rejected, SUBMIT));
        assertEquals(CallPhase.GUARD, refused.phase());
        assertInstanceOf(NullPointerException.class, refused.getCause());
        assertEquals(new FrozenFlow(Optional.of(rejected), Map.of(), List.of()), refused.flow());
    }
}
