package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.TickingClock;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * {@code now()} of a guard or an action answers one instant per transaction, read from the machine's clock the first
 * time a context of that transaction asks: the guards, the actions and the cascaded steps of one call, and the checks
 * its guards start, see the same instant, while a check of its own and each automatic attempt read again. A call that
 * never asks reads no clock, and a clock that throws or answers null fails only a call that asks.
 */
class ContextClockTest {

    private final List<Instant> seen = new ArrayList<>();

    private GuardResult stamp(GuardContext<Doc, DocStatus, DocTransition> ctx) {
        seen.add(ctx.now());
        return GuardResult.pass();
    }

    /** DRAFT submits and discards, REVIEW publishes, PUBLISHED archives; {@code edges} adds the edges under test. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> graph(
            UnaryOperator<GraphBuilder<Doc, DocStatus, DocTransition>> edges) {
        return edges.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition())
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, InstantSource clock) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id).clock(clock))
                .build().of(DOCS);
    }

    /** SUBMIT stamped by its guard and its action, the action of doc 1 cascading SUBMIT to {@code peer}. */
    private GraphDefinition<Doc, DocStatus, DocTransition> cascading(Doc peer) {
        return graph((GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                .transition(SUBMIT, DRAFT, REVIEW).guard("stamp", this::stamp)
                .action("act", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    seen.add(ctx.now());
                    if ("1".equals(ctx.entity().id())) {
                        ctx.cascade(DOCS, peer, SUBMIT);
                    }
                    return ActionOutcome.ok();
                })
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition());
    }

    @Test
    void guardsActionsAndCascadesOfOneCallSeeOneInstant() {
        TickingClock clock = new TickingClock();
        Doc peer = new Doc("2", DRAFT);

        Outcome<Doc, DocStatus, DocTransition> outcome =
                machine(cascading(peer), clock).execute(new Doc("1", DRAFT), SUBMIT);

        assertEquals(OutcomeStatus.APPLIED, outcome.status());
        assertEquals(List.of(TickingClock.at(1), TickingClock.at(1), TickingClock.at(1), TickingClock.at(1)), seen);
        assertEquals(1, clock.reads());
        assertEquals(REVIEW, peer.status());
    }

    @Test
    void aCallThatNeverAsksNeverReadsTheClock() {
        TickingClock clock = new TickingClock();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                        .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()), clock);

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());
        assertTrue(machine.eligibility(new Doc("1", REVIEW), PUBLISH).isEligible());
        assertEquals(0, clock.reads());
    }

    @Test
    void aCheckReadsItsOwnInstant() {
        TickingClock clock = new TickingClock();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(cascading(new Doc("2", DRAFT)), clock);

        assertTrue(machine.eligibility(new Doc("3", DRAFT), SUBMIT).isEligible());
        assertTrue(machine.eligibility(new Doc("3", DRAFT), SUBMIT).isEligible());

        assertEquals(List.of(TickingClock.at(1), TickingClock.at(2)), seen);
        assertEquals(2, clock.reads());
    }

    @Test
    void aCheckStartedByAGuardInheritsTheCallsInstant() {
        TickingClock clock = new TickingClock();
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc other = new Doc("9", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW)
                        .guard("probe", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.now());
                            return self.get().eligibility(other, DISCARD).isEligible()
                                    ? GuardResult.pass() : GuardResult.reject("the other doc is held");
                        })
                        .endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).guard("stamp", this::stamp).endTransition()
                        .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()), clock);
        self.set(machine);

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());

        assertEquals(List.of(TickingClock.at(1), TickingClock.at(1)), seen);
        assertEquals(1, clock.reads());
    }

    @Test
    void eachAutomaticAttemptReadsAgain() {
        TickingClock clock = new TickingClock();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW).guard("stamp", this::stamp).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                        .transition(PUBLISH, REVIEW, PUBLISHED).automatic().guard("stamp", this::stamp)
                        .endTransition()), clock);
        Doc doc = new Doc("1", DRAFT);

        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);

        assertEquals(OutcomeStatus.APPLIED, outcome.status());
        assertEquals(PUBLISHED, doc.status());
        assertEquals(List.of(TickingClock.at(1), TickingClock.at(2)), seen);
        assertEquals(2, clock.reads());
    }

    @Test
    void theTrailUsesTheSameClock() {
        TickingClock clock = new TickingClock();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = graph(
                (GraphBuilder<Doc, DocStatus, DocTransition> builder) -> builder
                        .transition(SUBMIT, DRAFT, REVIEW).guard("stamp", this::stamp).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                        .transition(PUBLISH, REVIEW, PUBLISHED).endTransition());
        StateMachine<Doc, DocStatus, DocTransition> machine = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id).clock(clock)
                        .recordTrail())
                .build().of(DOCS);

        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(new Doc("1", DRAFT), SUBMIT);

        assertEquals(List.of(TickingClock.at(1)), seen);
        assertEquals(TickingClock.at(2), outcome.flow().trail().get(0).at());
        assertEquals(2, clock.reads());
    }

    @Test
    void aThrowingClockAbortsOnlyWhenAsked() {
        IllegalStateException stopped = new IllegalStateException("stopped");
        InstantSource clock = () -> {
            throw stopped;
        };
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(cascading(new Doc("2", DRAFT)), clock);

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("3", REVIEW), PUBLISH).status());
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("3", DRAFT), SUBMIT));

        assertEquals(stopped, abort.guardFailure().orElseThrow());
    }

    @Test
    void aNullInstantAbortsOnlyWhenAsked() {
        InstantSource clock = () -> null;
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(cascading(new Doc("2", DRAFT)), clock);

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("3", REVIEW), PUBLISH).status());
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("3", DRAFT), SUBMIT));

        NullPointerException refused = assertInstanceOf(NullPointerException.class, abort.guardFailure().orElseThrow());
        assertEquals("the clock answered null", refused.getMessage());
    }
}
