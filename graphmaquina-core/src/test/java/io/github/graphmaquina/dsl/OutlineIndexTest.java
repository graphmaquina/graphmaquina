package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.TransitionKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The per-validation index has to answer exactly what the outline answers, invalid data included: two edges
 * that share a key stay both, in declaration order, because it is the validator that reports the duplicate.
 */
class OutlineIndexTest {

    private static final TransitionOutline FIRST =
            new TransitionOutline("A", "X", "B", TransitionKind.MOVE, Optional.empty());
    private static final TransitionOutline SECOND =
            new TransitionOutline("A", "X", "C", TransitionKind.MOVE, Optional.empty());

    private static OutlineIndex index() {
        MachineOutline outline = new MachineOutline("M", List.of("A", "B", "C"), List.of("X"), "A", Set.of(),
                Set.of(), List.of(FIRST, SECOND), List.of(), List.of());
        return new OutlineIndex(outline);
    }

    @Test
    void keepsEveryEdgeThatLeavesAStateInDeclarationOrder() {
        assertEquals(List.of(FIRST, SECOND), index().outgoing("A"));
    }

    @Test
    void readsTheDeclarationIndexOfStatesAndIdentifiers() {
        OutlineIndex index = index();
        assertEquals(0, index.stateIndex("A"));
        assertEquals(2, index.stateIndex("C"));
        assertEquals(0, index.transitionIndex("X"));
    }

    @Test
    void aStateWithoutEdgesAndAnUnknownNameAnswerTheSameWayTheOutlineDoes() {
        OutlineIndex index = index();
        assertEquals(List.of(), index.outgoing("B"));
        assertEquals(List.of(), index.outgoing("Z"));
        assertEquals(-1, index.stateIndex("Z"));
        assertEquals(-1, index.transitionIndex("Z"));
    }

    @Test
    void theTopologyCollapsesTheParallelEdgesAndKeepsTheDeclaredOrder() {
        assertEquals(List.of("B", "C"), List.copyOf(index().topology().successors("A")));
    }
}
