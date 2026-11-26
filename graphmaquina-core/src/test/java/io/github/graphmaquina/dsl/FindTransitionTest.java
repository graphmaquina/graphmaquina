package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What {@code findTransition} answers for every pair of a state and an identifier: the very spec the definition lists
 * for an edge of that pair, empty for a pair without one, and the refusal of an argument of another enum or a null,
 * on a small machine and on one whose initial state has more edges than a short scan would cover.
 */
class FindTransitionTest {

    /** States of the hub machine: the initial state leaves for each of the eleven others. */
    enum Hub { START, X1, X2, X3, X4, X5, X6, X7, X8, X9, X10, X11 }

    /** Identifiers of the hub machine; ABSENT is catalogued on purpose and carries no edge. */
    enum HubStep { GO1, GO2, GO3, GO4, GO5, GO6, GO7, GO8, GO9, GO10, GO11, BACK, ABSENT }

    /** The entity of the hub machine. */
    static final class Box { }

    static final MachineKey<Box, Hub, HubStep> HUBS = MachineKey.of(Box.class, Hub.class, HubStep.class);

    @Test
    void answersTheListedSpecOfEveryPairOfTheDocMachine() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = GraphBuilderTest.base().build();
        assertEveryPairAgreesWithTheList(graph, DocStatus.values(), DocTransition.values());
    }

    @Test
    void answersTheListedSpecOfEveryPairOfAStateWithManyEdges() {
        GraphBuilder<Box, Hub, HubStep> builder = StateGraph.define(HUBS).initial(Hub.START)
                .statePolicy(Hub.START, "start only leaves").allowsCycle(Hub.START);
        HubStep[] steps = HubStep.values();
        for (int target = 1; target < Hub.values().length; target++) {
            builder = builder.transition(steps[target - 1], Hub.START, Hub.values()[target]).endTransition();
        }
        for (int terminal = 2; terminal < Hub.values().length; terminal++) {
            builder = builder.terminal(Hub.values()[terminal]);
        }
        builder = builder.statePolicy(Hub.X1, "x1 only goes back")
                .transition(HubStep.BACK, Hub.X1, Hub.START).endTransition();
        GraphDefinition<Box, Hub, HubStep> graph = builder.build();
        assertEquals(12, graph.transitions().size());
        assertEveryPairAgreesWithTheList(graph, Hub.values(), steps);
        assertTrue(graph.findTransition(Hub.START, HubStep.ABSENT).isEmpty());
        assertTrue(graph.findTransition(Hub.X5, HubStep.GO5).isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refusesAStateOrAnIdentifierOfAnotherEnum() {
        GraphDefinition raw = GraphBuilderTest.base().build();
        IllegalArgumentException state = assertThrows(IllegalArgumentException.class,
                () -> raw.findTransition(Hub.START, DocTransition.SUBMIT));
        assertEquals("state must belong to " + DocStatus.class.getName() + ": START", state.getMessage());
        IllegalArgumentException transition = assertThrows(IllegalArgumentException.class,
                () -> raw.findTransition(DocStatus.DRAFT, HubStep.GO1));
        assertEquals("transition must belong to " + DocTransition.class.getName() + ": GO1",
                transition.getMessage());
        IllegalArgumentException stateFirst = assertThrows(IllegalArgumentException.class,
                () -> raw.findTransition(Hub.START, HubStep.GO1));
        assertEquals("state must belong to " + DocStatus.class.getName() + ": START", stateFirst.getMessage());
    }

    @Test
    void refusesANullStateOrIdentifier() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = GraphBuilderTest.base().build();
        assertThrows(NullPointerException.class, () -> graph.findTransition(null, DocTransition.SUBMIT));
        assertThrows(NullPointerException.class, () -> graph.findTransition(DocStatus.DRAFT, null));
    }

    @Test
    void answersOneSpecTheSameWayEveryTime() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = GraphBuilderTest.base().build();
        Optional<TransitionSpec<Doc, DocStatus, DocTransition>> first =
                graph.findTransition(DocStatus.REVIEW, DocTransition.PUBLISH);
        assertEquals(first, graph.findTransition(DocStatus.REVIEW, DocTransition.PUBLISH));
        assertSame(first.orElseThrow(), graph.findTransition(DocStatus.REVIEW, DocTransition.PUBLISH).orElseThrow());
        assertEquals(DOCS, graph.key());
    }

    /** Every pair answers the spec of {@code transitions()} with that state and identifier, by reference, or empty. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void assertEveryPairAgreesWithTheList(
            GraphDefinition<E, S, T> graph, S[] states, T[] identifiers) {
        for (S from : states) {
            for (T identifier : identifiers) {
                Optional<TransitionSpec<E, S, T>> listed = listed(graph, from, identifier);
                Optional<TransitionSpec<E, S, T>> found = graph.findTransition(from, identifier);
                assertEquals(listed.isPresent(), found.isPresent(), from + "/" + identifier);
                listed.ifPresent((TransitionSpec<E, S, T> spec) ->
                        assertSame(spec, found.orElseThrow(), from + "/" + identifier));
            }
        }
    }

    /** The last spec of {@code transitions()} from {@code from} with {@code identifier}; empty when none has both. */
    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<TransitionSpec<E, S, T>> listed(
            GraphDefinition<E, S, T> graph, S from, T identifier) {
        Optional<TransitionSpec<E, S, T>> listed = Optional.empty();
        for (TransitionSpec<E, S, T> spec : graph.transitions()) {
            if (spec.from() == from && spec.transition() == identifier) {
                listed = Optional.of(spec);
            }
        }
        return listed;
    }
}
