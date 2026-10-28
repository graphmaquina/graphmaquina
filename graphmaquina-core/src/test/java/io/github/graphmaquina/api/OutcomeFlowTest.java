package io.github.graphmaquina.api;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.internal.FrozenFlow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every outcome answers {@link Outcome#flow()}: an applied, failed or rejected outcome carries it as a component, and
 * its constructors without it give {@link FlowView#empty()}; an ignored outcome answers the empty view.
 * The flow is part of an outcome's value (spec D6): two outcomes are equal only when their flows are.
 */
class OutcomeFlowTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final Doc DOC = new Doc("1", DRAFT);

    /** A failure kind for the failed outcomes built here. */
    record Declined() implements FailureKind { }

    private static FlowView frozen(String batch) {
        Map<FlowKey<?>, Object> values = new LinkedHashMap<>();
        values.put(BATCH, batch);
        return new FrozenFlow(Optional.of(DOC), values);
    }

    private static Outcome.Applied<Doc, DocStatus, DocTransition> applied(FlowView flow) {
        return new Outcome.Applied<>(DOC, DRAFT, SUBMIT, REVIEW, List.of(), List.of(), List.of(), flow, List.of());
    }

    private static Outcome.Failed<Doc, DocStatus, DocTransition> failed(FlowView flow) {
        return new Outcome.Failed<>(DOC, DRAFT, SUBMIT, REVIEW, DISCARD, ARCHIVED, "declined", Optional.empty(),
                List.of(), List.of(), Optional.of(Declined.class), List.of(), flow, List.of());
    }

    private static Outcome.Rejected<Doc, DocStatus, DocTransition> rejected(FlowView flow) {
        return new Outcome.Rejected<>(DOC, DRAFT, SUBMIT, "closed", RejectionKind.GUARD, Optional.of("Open"), flow);
    }

    /** Spec D8: every constructor that existed before the flow keeps its parameters and gives the empty view. */
    @Test
    void theConstructorsWithoutAFlowGiveTheEmptyView() {
        List<Outcome<Doc, DocStatus, DocTransition>> outcomes = List.of(
                new Outcome.Applied<>(DOC, DRAFT, SUBMIT, REVIEW, List.of(), List.of()),
                new Outcome.Applied<>(DOC, DRAFT, SUBMIT, REVIEW, List.of(), List.of(), List.of()),
                new Outcome.Failed<>(DOC, DRAFT, SUBMIT, REVIEW, DISCARD, ARCHIVED, "declined", Optional.empty(),
                        List.of(), List.of()),
                new Outcome.Failed<>(DOC, DRAFT, SUBMIT, REVIEW, DISCARD, ARCHIVED, "declined", Optional.empty(),
                        List.of(), List.of(), Optional.of(Declined.class)),
                new Outcome.Failed<>(DOC, DRAFT, SUBMIT, REVIEW, DISCARD, ARCHIVED, "declined", Optional.empty(),
                        List.of(), List.of(), Optional.of(Declined.class), List.of()),
                new Outcome.Rejected<>(DOC, DRAFT, SUBMIT, "closed", RejectionKind.GUARD, Optional.of("Open")),
                new Outcome.Ignored<>(DOC, DRAFT, IgnoreReason.NO_WATCH));
        for (Outcome<Doc, DocStatus, DocTransition> outcome : outcomes) {
            assertSame(FlowView.empty(), outcome.flow(), outcome.code().name());
        }
    }

    @Test
    void theCanonicalConstructorsKeepTheFlowTheyAreGiven() {
        FlowView flow = frozen("b-1");
        assertSame(flow, applied(flow).flow());
        assertSame(flow, failed(flow).flow());
        assertSame(flow, rejected(flow).flow());
    }

    @Test
    void aNullFlowIsRefused() {
        assertEquals("flow is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> applied(null)).getMessage());
        assertEquals("flow is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> failed(null)).getMessage());
        assertEquals("flow is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> rejected(null)).getMessage());
    }

    /**
     * Spec D6: outcomes stay values. Two outcomes whose flows are equal are equal, with the same hash code, and a
     * flow that differs, in a value or in having an entity, makes them differ.
     */
    @Test
    void twoOutcomesAreEqualOnlyWhenTheirFlowsAre() {
        assertEquals(applied(frozen("b-1")), applied(frozen("b-1")));
        assertEquals(applied(frozen("b-1")).hashCode(), applied(frozen("b-1")).hashCode());
        assertEquals(failed(frozen("b-1")), failed(frozen("b-1")));
        assertEquals(rejected(frozen("b-1")), rejected(frozen("b-1")));
        assertNotEquals(applied(frozen("b-1")), applied(frozen("b-2")));
        assertNotEquals(failed(frozen("b-1")), failed(frozen("b-2")));
        assertNotEquals(rejected(frozen("b-1")), rejected(frozen("b-2")));
        assertNotEquals(applied(frozen("b-1")), applied(FlowView.empty()));
        assertNotEquals(applied(new FrozenFlow(Optional.of(DOC), Map.of())), applied(FlowView.empty()));
        assertEquals(applied(FlowView.empty()), new Outcome.Applied<>(DOC, DRAFT, SUBMIT, REVIEW, List.of(),
                List.of()));
    }
}
