package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GatewayException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * One standalone machine over the doc fixture: what each outcome writes, guard order, the failure route and its
 * limits, aborts, the commit's pre-read and setter failures, reentrancy, how long and where a context and a peer
 * view stay usable, and self-loops.
 */
class SingleMachineTest {

    /**
     * A transition action that records each call as {@code from-identifier>to@depth}, runs {@code onCall}, and answers
     * with {@code behaviour}, ok by default.
     */
    static final class Probe implements TransitionAction<Doc, DocStatus, DocTransition> {
        Supplier<ActionOutcome> behaviour = ActionOutcome::ok;
        Consumer<TransitionContext<Doc, DocStatus, DocTransition>> onCall =
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> { };
        final List<String> calls = new ArrayList<>();

        @Override
        public ActionOutcome process(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
            calls.add(ctx.from() + "-" + ctx.transition() + ">" + ctx.to() + "@" + ctx.depth());
            onCall.accept(ctx);
            return behaviour.get();
        }
    }

    /** A transition guard that counts its calls and answers with {@code verdict}, pass by default. */
    static final class GuardProbe implements TransitionGuard<Doc, DocStatus, DocTransition> {
        Supplier<GuardResult> verdict = GuardResult::pass;
        int calls;

        @Override
        public GuardResult test(GuardContext<Doc, DocStatus, DocTransition> ctx) {
            calls++;
            return verdict.get();
        }
    }

    final Probe submit = new Probe();
    final Probe publish = new Probe();
    final Probe discard = new Probe();
    final GuardProbe g1 = new GuardProbe();
    final GuardProbe g2 = new GuardProbe();
    final GuardProbe archiveGuard = new GuardProbe();
    final GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
            .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
            .statePolicy(DRAFT, "a draft can only be sent to review or discarded")
            .statePolicy(REVIEW, "in review it can only be published or sent back")
            .statePolicy(PUBLISHED, "published can only be archived")
            .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit).guard("G1", g1).guard("G2", g2)
                .onFailure(DISCARD).failsOn(GatewayException.class).endTransition()
            .transition(DISCARD, DRAFT, ARCHIVED).action("Discard", discard).guard("ArchiveGuard", archiveGuard)
                .endTransition()
            .transition(PUBLISH, REVIEW, PUBLISHED).action("Publish", publish).endTransition()
            .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
            .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
            .build();
    final StateMachine<Doc, DocStatus, DocTransition> machine =
            StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    final List<String> writes = new ArrayList<>();

    /** The same machine with a setter that records every write, and that throws for one identity when asked to. */
    private StateMachine<Doc, DocStatus, DocTransition> recordingWrites(String throwingId) {
        return StateMachines.from(graph).state(Doc::status, (Doc doc, DocStatus status) -> {
            writes.add(doc.id() + ":" + status);
            if (doc.id().equals(throwingId)) {
                throw new IllegalStateException("the store rejected " + doc.id());
            }
            doc.setStatus(status);
        }).build();
    }

    private static List<DocTransition> transitionsOf(List<TransitionRecord<?, ?, ?>> records) {
        List<DocTransition> transitions = new ArrayList<>();
        for (TransitionRecord<?, ?, ?> record : records) {
            transitions.add((DocTransition) record.transition());
        }
        return transitions;
    }

    @Test
    void moveIsAppliedAndWritten() {
        Doc doc = new Doc("1", REVIEW);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SEND_BACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Doc, DocStatus, DocTransition> applied = (Outcome.Applied<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(REVIEW, applied.from());
        assertEquals(DRAFT, applied.to());
        assertEquals(DRAFT, doc.status());
        assertEquals(1, applied.records().size());
        TransitionRecord<?, ?, ?> record = applied.records().get(0);
        assertEquals(TransitionKind.MOVE, record.kind());
        assertEquals(SEND_BACK, record.transition());
        assertEquals(Optional.empty(), record.action());
        assertEquals(0, record.depth());
        assertEquals(DOCS, record.key());
    }

    /**
     * There is no implicit staying in 2.0: asking for an identifier that leaves no edge from the current state
     * is a rejection, not an ignore. Nothing runs and the state is untouched, which is the guarantee that used
     * to be carried by the STAY diagonal.
     */
    @Test
    void anIdentifierWithNoEdgeFromHereIsRejectedAndNothingRuns() {
        Doc doc = new Doc("1", REVIEW);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected = (Outcome.Rejected<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(RejectionKind.NO_TRANSITION, rejected.kind());
        assertEquals(REVIEW, doc.status());
        assertEquals(List.of(), submit.calls);
    }

    @Test
    void absentEdgeIsRejectedWithTheStatePolicyReason() {
        Doc doc = new Doc("1", REVIEW);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, ARCHIVE);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected = (Outcome.Rejected<Doc, DocStatus, DocTransition>) outcome;
        assertEquals("in review it can only be published or sent back", rejected.reason());
        assertEquals(Optional.empty(), rejected.guard());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void guardsRunInOrderAndTheFirstRejectCuts() {
        g1.verdict = () -> GuardResult.reject("no title");
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected = (Outcome.Rejected<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(RejectionKind.GUARD, rejected.kind());
        assertEquals(Optional.of("G1"), rejected.guard());
        assertEquals("no title", rejected.reason());
        assertEquals(1, g1.calls);
        assertEquals(0, g2.calls);
        assertEquals(List.of(), submit.calls);

        g1.verdict = GuardResult::pass;
        g2.verdict = () -> GuardResult.reject("no body");
        Outcome<Doc, DocStatus, DocTransition> secondOutcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Rejected.class, secondOutcome);
        Outcome.Rejected<Doc, DocStatus, DocTransition> second = (Outcome.Rejected<Doc, DocStatus, DocTransition>) secondOutcome;
        assertEquals(Optional.of("G2"), second.guard());
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void actionOkIsAppliedWithContext() {
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Doc, DocStatus, DocTransition> applied = (Outcome.Applied<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(List.of("DRAFT-SUBMIT>REVIEW@0"), submit.calls);
        assertEquals(REVIEW, doc.status());
        assertEquals(Optional.of("Submit"), applied.records().get(0).action());
        assertEquals(TransitionKind.ACTION, applied.records().get(0).kind());
    }

    @Test
    void failedOutcomeTakesTheFailureRouteWithoutItsGuards() {
        submit.behaviour = () -> ActionOutcome.failed("no content");
        archiveGuard.verdict = () -> GuardResult.reject("should not be evaluated");
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus, DocTransition> failed = (Outcome.Failed<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(DRAFT, failed.from());
        assertEquals(SUBMIT, failed.transition());
        assertEquals(REVIEW, failed.to());
        assertEquals(DISCARD, failed.failureTransition());
        assertEquals(ARCHIVED, failed.movedTo());
        assertEquals("no content", failed.reason());
        assertEquals(Optional.empty(), failed.cause());
        assertEquals(ARCHIVED, doc.status());
        assertEquals(List.of("DRAFT-DISCARD>ARCHIVED@0"), discard.calls);
        assertEquals(0, archiveGuard.calls);
        assertEquals(1, failed.records().size());
        assertEquals(Optional.of("Discard"), failed.records().get(0).action());
    }

    @Test
    void listedExceptionIsADomainFailure() {
        submit.behaviour = () -> { throw new GatewayException("timeout"); };
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus, DocTransition> failed = (Outcome.Failed<Doc, DocStatus, DocTransition>) outcome;
        assertInstanceOf(GatewayException.class, failed.cause().orElseThrow());
        assertEquals("GatewayException: timeout", failed.reason());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void aWrappedListedExceptionIsUnwrappedBeforeFailsOn() {
        submit.behaviour = () -> { throw new CompletionException(new GatewayException("x")); };
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus, DocTransition> failed = (Outcome.Failed<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(ARCHIVED, failed.movedTo());
        assertInstanceOf(GatewayException.class, failed.cause().orElseThrow());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void unlistedExceptionAbortsWithJournal() {
        IllegalStateException boom = new IllegalStateException("boom");
        submit.behaviour = () -> { throw boom; };
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(boom, error.getCause());
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of(), discard.calls);
        assertEquals(Optional.of("Submit"), error.action());
        assertEquals(Optional.of(SUBMIT), error.transition());
        assertEquals(Optional.of(REVIEW), error.to());
        assertEquals(1, error.journal().size());
        ActionRecord record = error.journal().get(0);
        assertEquals("Submit", record.action());
        assertEquals(SUBMIT, record.transition());
        assertEquals(ActionStatus.THREW, record.status());
        assertEquals(List.of("Doc/DocStatus#@" + Integer.toHexString(System.identityHashCode(doc))), error.cascadePath());
    }

    @Test
    void failedWithoutRouteIsUnhandled() {
        publish.behaviour = () -> ActionOutcome.failed("reviewer absent");
        Doc doc = new Doc("1", REVIEW);
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class, () -> machine.execute(doc, PUBLISH));
        assertEquals("reviewer absent", error.reason());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void failureInsideTheFailureRouteAbortsWithOriginalState() {
        submit.behaviour = () -> ActionOutcome.failed("no content");
        discard.behaviour = () -> ActionOutcome.failed("could not discard");
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        assertTrue(error.getMessage().contains("failure route"));
        assertTrue(error.getMessage().contains("no content"));
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of(ActionStatus.FAILED, ActionStatus.FAILED),
                error.journal().stream().map(ActionRecord::status).toList());
    }

    @Test
    void directCallToTheFailureEdgeEvaluatesItsGuards() {
        archiveGuard.verdict = () -> GuardResult.reject("not yet");
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, DISCARD);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected = (Outcome.Rejected<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(Optional.of("ArchiveGuard"), rejected.guard());
        assertEquals(List.of(), discard.calls);
    }

    @Test
    void manualSetterInsideAnActionFailsTheCommit() {
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.entity().setStatus(PUBLISHED);
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(doc, SUBMIT));
        assertEquals(List.of(), error.applied());
        assertEquals(List.of(doc), error.pending());
        assertTrue(error.getMessage().contains(
                "an action called the setter, or a concurrent transition on the same entity wrote it first"));
    }

    /**
     * The pre-read covers every entity of the commit before the first write. A peer whose state moved under the
     * transaction stops phase 2 with nothing applied, and the entity that would have been written first is
     * left exactly as it was.
     */
    @Test
    void aPreReadThatFailsLeavesEverySetterUncalled() {
        Doc peer = new Doc("2", REVIEW);
        StateMachine<Doc, DocStatus, DocTransition> recording = recordingWrites("");
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            ctx.cascade(DOCS, peer, PUBLISH);
            peer.setStatus(ARCHIVED);
        };
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> recording.execute(doc, SUBMIT));
        assertEquals(List.of(), error.applied());
        assertEquals(List.of(doc, peer), error.pending());
        assertEquals(List.of(), writes);
        assertEquals(DRAFT, doc.status());
        assertTrue(error.getMessage().contains(
                "an action called the setter, or a concurrent transition on the same entity wrote it first"));
    }

    /**
     * A setter that throws stops phase 2 where it stood: what was written is applied, the entity whose setter threw
     * is named, and nothing is left pending here.
     */
    @Test
    void aSetterThatThrowsReportsExactlyWhatWasAppliedAndWhatWasNot() {
        Doc peer = new Doc("2", REVIEW);
        StateMachine<Doc, DocStatus, DocTransition> recording = recordingWrites("2");
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, peer, PUBLISH);
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> recording.execute(doc, SUBMIT));
        assertEquals(CallPhase.COMMIT, error.phase());
        assertEquals(StatesWritten.PARTIAL, error.statesWritten());
        assertEquals(List.of(doc), error.applied());
        assertEquals(Optional.of(peer), error.failedEntity());
        assertEquals(List.of(), error.pending());
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals(List.of("1:REVIEW", "2:PUBLISHED"), writes);
        assertEquals(REVIEW, doc.status());
        assertEquals(REVIEW, peer.status());
    }

    /** Two hops of one entity are two records and a single write, with the state its last hop ends in. */
    @Test
    void everyEntityIsWrittenOnceWithTheStateOfItsLastHop() {
        Doc peer = new Doc("2", REVIEW);
        StateMachine<Doc, DocStatus, DocTransition> recording = recordingWrites("");
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            ctx.cascade(DOCS, peer, PUBLISH);
            ctx.cascade(DOCS, peer, ARCHIVE);
        };
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = recording.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Doc, DocStatus, DocTransition> applied = (Outcome.Applied<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(List.of("1:REVIEW", "2:ARCHIVED"), writes);
        assertEquals(REVIEW, doc.status());
        assertEquals(ARCHIVED, peer.status());
        assertEquals(List.of(SUBMIT, PUBLISH, ARCHIVE), transitionsOf(applied.records()));
        assertEquals(List.of(0, 1, 1), applied.records().stream().map(TransitionRecord::depth).toList());
    }

    /** A failure route rolls its cascaded hops back before phase 2: the discarded peer is not part of the commit. */
    @Test
    void aFailureRouteCommitsWithoutTheHopsItDiscarded() {
        Doc peer = new Doc("2", REVIEW);
        StateMachine<Doc, DocStatus, DocTransition> recording = recordingWrites("");
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, peer, PUBLISH);
        submit.behaviour = () -> { throw new GatewayException("timeout"); };
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = recording.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus, DocTransition> failed = (Outcome.Failed<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(List.of("1:ARCHIVED"), writes);
        assertEquals(ARCHIVED, doc.status());
        assertEquals(REVIEW, peer.status());
        assertEquals(List.of(DISCARD), transitionsOf(failed.records()));
    }

    @Test
    void reentrancyIsDetected() {
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> machine.execute(ctx.entity(), DISCARD);
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(doc, SUBMIT));
        assertInstanceOf(ReentrantTransitionException.class, error.getCause());
        assertEquals(DRAFT, doc.status());
        Outcome<Doc, DocStatus, DocTransition> later = machine.execute(new Doc("2", REVIEW), SEND_BACK);
        assertInstanceOf(Outcome.Applied.class, later);
    }

    @Test
    void nullStateIsUninitialized() {
        Doc doc = new Doc("1", null);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(doc, SUBMIT));
        assertInstanceOf(UninitializedStateException.class, error.getCause());
        assertEquals(CallPhase.RESOLVE, error.phase());
        assertEquals(Optional.empty(), error.from());
    }

    @Test
    void contextIsStaleAfterCommit() {
        AtomicReference<TransitionContext<Doc, DocStatus, DocTransition>> captured = new AtomicReference<>();
        submit.onCall = captured::set;
        machine.execute(new Doc("1", DRAFT), SUBMIT);
        assertThrows(StaleContextException.class, () -> captured.get().peer(DOCS));
    }

    @Test
    void peerViewIsStaleAfterCommit() {
        AtomicReference<PeerView<Doc, DocStatus, DocTransition>> captured = new AtomicReference<>();
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> captured.set(ctx.peer(DOCS));
        Doc doc = new Doc("1", DRAFT);
        machine.execute(doc, SUBMIT);
        PeerView<Doc, DocStatus, DocTransition> view = captured.get();
        assertThrows(StaleContextException.class, () -> view.stateOf(doc));
    }

    @Test
    void contextRejectsOtherThreads() {
        AtomicReference<RuntimeException> captured = new AtomicReference<>();
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            Thread other = new Thread(() -> {
                try {
                    ctx.peer(DOCS);
                } catch (RuntimeException e) {
                    captured.set(e);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        machine.execute(new Doc("1", DRAFT), SUBMIT);
        assertInstanceOf(WrongThreadException.class, captured.get());
    }

    @Test
    void selfPeerViewReadsThePendingState() {
        AtomicReference<DocStatus> seen = new AtomicReference<>();
        AtomicReference<DocStatus> raw = new AtomicReference<>();
        submit.onCall = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            seen.set(ctx.peer(DOCS).stateOf(ctx.entity()));
            raw.set(ctx.entity().status());
        };
        machine.execute(new Doc("1", DRAFT), SUBMIT);
        assertEquals(REVIEW, seen.get());
        assertEquals(DRAFT, raw.get());
    }

    /**
     * An explicit self-loop is a real edge: it is Applied, its action runs once, and the commit writes its state
     * through the setter once, although the entity stays where it was.
     */
    @Test
    void anExplicitSelfLoopIsAppliedAndWritesItsState() {
        Probe retouch = new Probe();
        GraphDefinition<Doc, DocStatus, DocTransition> looping = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is retouched or published")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).action("Retouch", retouch).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> loops = StateMachines.from(looping)
                .state(Doc::status, (Doc written, DocStatus status) -> {
                    writes.add(written.id() + ":" + status);
                    written.setStatus(status);
                })
                .build();
        Doc doc = new Doc("1", REVIEW);
        Outcome<Doc, DocStatus, DocTransition> outcome = loops.execute(doc, RETOUCH);
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(List.of("REVIEW-RETOUCH>REVIEW@0"), retouch.calls);
        assertEquals(List.of("1:REVIEW"), writes);
        assertEquals(REVIEW, doc.status());
    }
}
