package io.github.graphmaquina.fixtures;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.InstantSource;

/** What the tests of the trail of a call share: a doc machine that records its trail, and the reading of a hop. */
public final class TrailFixtures {

    private TrailFixtures() { }

    /** The documents machine, recording its trail on {@code clock}. */
    public static StateMachines.Builder<Doc, DocStatus, DocTransition> recording(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, InstantSource clock) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id).recordTrail(clock);
    }

    /** The record of the hop that {@code entry} holds. */
    public static TransitionRecord<?, ?, ?> hopOf(FlowEntry entry) {
        return assertInstanceOf(FlowEntry.Hop.class, entry).hop();
    }
}
