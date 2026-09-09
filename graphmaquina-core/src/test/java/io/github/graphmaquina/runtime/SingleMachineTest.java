package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
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
import io.github.graphmaquina.fixtures.GatewayException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SingleMachineTest {

    static final class Probe implements TransitionAction<Doc, DocStatus> {
        Supplier<ActionOutcome> behaviour = ActionOutcome::ok;
        Consumer<TransitionContext<Doc, DocStatus>> onCall = (TransitionContext<Doc, DocStatus> ctx) -> { };
        final List<String> calls = new ArrayList<>();

        @Override
        public ActionOutcome process(TransitionContext<Doc, DocStatus> ctx) {
            calls.add(ctx.from() + ">" + ctx.to() + "@" + ctx.depth());
            onCall.accept(ctx);
            return behaviour.get();
        }
    }

    static final class GuardProbe implements TransitionGuard<Doc, DocStatus> {
        Supplier<GuardResult> verdict = GuardResult::pass;
        int calls;

        @Override
        public GuardResult test(GuardContext<Doc, DocStatus> ctx) {
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
    final GraphDefinition<Doc, DocStatus> graph = StateGraph.define(DOCS)
            .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
            .from(DRAFT)
                .to(REVIEW).action("Submit", submit).guard("G1", g1).guard("G2", g2)
                           .onFailure(ARCHIVED).failsOn(GatewayException.class)
                .to(ARCHIVED).action("Discard", discard).guard("ArchiveGuard", archiveGuard)
                .stay()
                .forbidOthers("a draft can only be sent to review or discarded")
            .from(REVIEW)
                .to(PUBLISHED).action("Publish", publish)
                .to(DRAFT).move()
                .stay()
                .forbidOthers("in review it can only be published or sent back")
            .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("published can only be archived")
            .from(ARCHIVED).stay().forbidOthers("archived is final")
            .build();
    final StateMachine<Doc, DocStatus> machine = StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();

    @Test
    void moveIsAppliedAndWritten() {
        Doc doc = new Doc("1", REVIEW);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, DRAFT);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Doc, DocStatus> applied = (Outcome.Applied<Doc, DocStatus>) outcome;
        assertEquals(REVIEW, applied.from());
        assertEquals(DRAFT, applied.to());
        assertEquals(DRAFT, doc.status());
        assertEquals(1, applied.records().size());
        TransitionRecord<?, ?> record = applied.records().get(0);
        assertEquals(EdgeKind.MOVE, record.kind());
        assertEquals(Optional.empty(), record.action());
        assertEquals(0, record.depth());
        assertEquals(DOCS, record.key());
    }

    @Test
    void stayIsIgnored() {
        Doc doc = new Doc("1", REVIEW);
        assertInstanceOf(Outcome.Ignored.class, machine.transitionTo(doc, REVIEW));
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void forbidIsRejectedWithReason() {
        Doc doc = new Doc("1", REVIEW);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, ARCHIVED);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus> rejected = (Outcome.Rejected<Doc, DocStatus>) outcome;
        assertEquals("in review it can only be published or sent back", rejected.reason());
        assertEquals(Optional.empty(), rejected.guard());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void guardsRunInOrderAndTheFirstRejectCuts() {
        g1.verdict = () -> GuardResult.reject("no title");
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus> rejected = (Outcome.Rejected<Doc, DocStatus>) outcome;
        assertEquals(Optional.of("G1"), rejected.guard());
        assertEquals("no title", rejected.reason());
        assertEquals(1, g1.calls);
        assertEquals(0, g2.calls);
        assertEquals(List.of(), submit.calls);

        g1.verdict = GuardResult::pass;
        g2.verdict = () -> GuardResult.reject("no body");
        Outcome<Doc, DocStatus> secondOutcome = machine.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Rejected.class, secondOutcome);
        Outcome.Rejected<Doc, DocStatus> second = (Outcome.Rejected<Doc, DocStatus>) secondOutcome;
        assertEquals(Optional.of("G2"), second.guard());
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void actionOkIsAppliedWithContext() {
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Doc, DocStatus> applied = (Outcome.Applied<Doc, DocStatus>) outcome;
        assertEquals(List.of("DRAFT>REVIEW@0"), submit.calls);
        assertEquals(REVIEW, doc.status());
        assertEquals(Optional.of("Submit"), applied.records().get(0).action());
        assertEquals(EdgeKind.ACTION, applied.records().get(0).kind());
    }

    @Test
    void failedOutcomeTakesTheFailureRouteWithoutItsGuards() {
        submit.behaviour = () -> ActionOutcome.failed("no content");
        archiveGuard.verdict = () -> GuardResult.reject("should not be evaluated");
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus> failed = (Outcome.Failed<Doc, DocStatus>) outcome;
        assertEquals(DRAFT, failed.from());
        assertEquals(REVIEW, failed.to());
        assertEquals(ARCHIVED, failed.movedTo());
        assertEquals("no content", failed.reason());
        assertEquals(Optional.empty(), failed.cause());
        assertEquals(ARCHIVED, doc.status());
        assertEquals(List.of("DRAFT>ARCHIVED@0"), discard.calls);
        assertEquals(0, archiveGuard.calls);
        assertEquals(1, failed.records().size());
        assertEquals(Optional.of("Discard"), failed.records().get(0).action());
    }

    @Test
    void listedExceptionIsADomainFailure() {
        submit.behaviour = () -> { throw new GatewayException("timeout"); };
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus> failed = (Outcome.Failed<Doc, DocStatus>) outcome;
        assertInstanceOf(GatewayException.class, failed.cause().orElseThrow());
        assertEquals("GatewayException: timeout", failed.reason());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void aWrappedListedExceptionIsUnwrappedBeforeFailsOn() {
        submit.behaviour = () -> { throw new CompletionException(new GatewayException("x")); };
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus> failed = (Outcome.Failed<Doc, DocStatus>) outcome;
        assertEquals(ARCHIVED, failed.movedTo());
        assertInstanceOf(GatewayException.class, failed.cause().orElseThrow());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void unlistedExceptionAbortsWithJournal() {
        IllegalStateException boom = new IllegalStateException("boom");
        submit.behaviour = () -> { throw boom; };
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.transitionTo(doc, REVIEW));
        assertSame(boom, error.getCause());
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of(), discard.calls);
        assertEquals(Optional.of("Submit"), error.action());
        assertEquals(1, error.journal().size());
        ActionRecord record = error.journal().get(0);
        assertEquals("Submit", record.action());
        assertEquals(ActionStatus.THREW, record.status());
        assertEquals(List.of("Doc/DocStatus#@" + Integer.toHexString(System.identityHashCode(doc))), error.cascadePath());
    }

    @Test
    void failedWithoutRouteIsUnhandled() {
        publish.behaviour = () -> ActionOutcome.failed("reviewer absent");
        Doc doc = new Doc("1", REVIEW);
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class, () -> machine.transitionTo(doc, PUBLISHED));
        assertEquals("reviewer absent", error.reason());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void failureInsideTheFailureRouteAbortsWithOriginalState() {
        submit.behaviour = () -> ActionOutcome.failed("no content");
        discard.behaviour = () -> ActionOutcome.failed("could not discard");
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.transitionTo(doc, REVIEW));
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
        Outcome<Doc, DocStatus> outcome = machine.transitionTo(doc, ARCHIVED);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus> rejected = (Outcome.Rejected<Doc, DocStatus>) outcome;
        assertEquals(Optional.of("ArchiveGuard"), rejected.guard());
        assertEquals(List.of(), discard.calls);
    }

    @Test
    void manualSetterInsideAnActionFailsTheCommit() {
        submit.onCall = (TransitionContext<Doc, DocStatus> ctx) -> ctx.entity().setStatus(PUBLISHED);
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.transitionTo(doc, REVIEW));
        assertEquals(List.of(), error.applied());
        assertEquals(List.of(doc), error.pending());
        assertTrue(error.getMessage().contains("an action must not call the setter"));
    }

    @Test
    void reentrancyIsDetected() {
        submit.onCall = (TransitionContext<Doc, DocStatus> ctx) -> machine.transitionTo(ctx.entity(), ARCHIVED);
        Doc doc = new Doc("1", DRAFT);
        assertThrows(ReentrantTransitionException.class, () -> machine.transitionTo(doc, REVIEW));
        assertEquals(DRAFT, doc.status());
        Outcome<Doc, DocStatus> later = machine.transitionTo(new Doc("2", REVIEW), DRAFT);
        assertInstanceOf(Outcome.Applied.class, later);
    }

    @Test
    void nullStateIsUninitialized() {
        Doc doc = new Doc("1", null);
        assertThrows(UninitializedStateException.class, () -> machine.transitionTo(doc, REVIEW));
    }

    @Test
    void contextIsStaleAfterCommit() {
        AtomicReference<TransitionContext<Doc, DocStatus>> captured = new AtomicReference<>();
        submit.onCall = captured::set;
        machine.transitionTo(new Doc("1", DRAFT), REVIEW);
        assertThrows(StaleContextException.class, () -> captured.get().peer(DOCS));
    }

    @Test
    void peerViewIsStaleAfterCommit() {
        AtomicReference<PeerView<Doc, DocStatus>> captured = new AtomicReference<>();
        submit.onCall = (TransitionContext<Doc, DocStatus> ctx) -> captured.set(ctx.peer(DOCS));
        Doc doc = new Doc("1", DRAFT);
        machine.transitionTo(doc, REVIEW);
        PeerView<Doc, DocStatus> view = captured.get();
        assertThrows(StaleContextException.class, () -> view.stateOf(doc));
    }

    @Test
    void contextRejectsOtherThreads() {
        AtomicReference<RuntimeException> captured = new AtomicReference<>();
        submit.onCall = (TransitionContext<Doc, DocStatus> ctx) -> {
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
        machine.transitionTo(new Doc("1", DRAFT), REVIEW);
        assertInstanceOf(WrongThreadException.class, captured.get());
    }

    @Test
    void selfPeerViewReadsThePendingState() {
        AtomicReference<DocStatus> seen = new AtomicReference<>();
        AtomicReference<DocStatus> raw = new AtomicReference<>();
        submit.onCall = (TransitionContext<Doc, DocStatus> ctx) -> {
            seen.set(ctx.peer(DOCS).stateOf(ctx.entity()));
            raw.set(ctx.entity().status());
        };
        machine.transitionTo(new Doc("1", DRAFT), REVIEW);
        assertEquals(REVIEW, seen.get());
        assertEquals(DRAFT, raw.get());
    }
}
