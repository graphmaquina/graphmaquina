package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Flow fixture for this module's own tests, built with the DSL and run by a real standalone machine: a parcel that
 * {@code SHIP} moves from {@code PACKED} to {@code SHIPPED}, which {@code DELIVER} or {@code HOLD} then leave. The
 * action {@code label} of {@code SHIP} puts {@link #TRACKING} and then {@link #WEIGHT} into the call's flow; the guard
 * {@code signed} of {@code DELIVER} always rejects; the action {@code inspect} of {@code HOLD} puts {@link #NOTE} and
 * then throws an {@link IllegalStateException} that its edge does not declare, so the call aborts. A machine built with
 * a clock records the trail of its calls on it. Two parcels with the same id are equal, so a test can tell selection by
 * reference from selection by equality.
 */
public final class Parcels {

    /** States of a parcel. */
    public enum Spot { PACKED, SHIPPED, DELIVERED, HELD }

    /** Identifiers of the parcel machine. */
    public enum Move { SHIP, DELIVER, HOLD }

    /** A parcel, equal to every parcel with the same id whatever its state. */
    @AllArgsConstructor
    public static final class Parcel {

        private final String id;
        @Getter
        private Spot spot;

        public void setSpot(Spot spot) { this.spot = spot; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Parcel parcel && parcel.id.equals(id);
        }

        @Override
        public int hashCode() { return id.hashCode(); }

        @Override
        public String toString() { return "Parcel#" + id; }
    }

    public static final MachineKey<Parcel, Spot, Move> PARCELS = MachineKey.of(Parcel.class, Spot.class, Move.class);

    /** Put by the action of {@code SHIP}, first. */
    public static final FlowKey<String> TRACKING = FlowKey.of("tracking", String.class);

    /** Put by the action of {@code SHIP}, second. */
    public static final FlowKey<Integer> WEIGHT = FlowKey.of("weight", Integer.class);

    /** Put by the action of {@code HOLD} before it throws. */
    public static final FlowKey<String> NOTE = FlowKey.of("note", String.class);

    public static final GraphDefinition<Parcel, Spot, Move> GRAPH = StateGraph.define(PARCELS)
            .initial(Spot.PACKED)
            .terminal(Spot.DELIVERED).terminal(Spot.HELD)
            .statePolicy(Spot.PACKED, "ship the parcel first")
            .statePolicy(Spot.SHIPPED, "deliver or hold the parcel")
            .transition(Move.SHIP, Spot.PACKED, Spot.SHIPPED)
                .action("label", (TransitionContext<Parcel, Spot, Move> ctx) -> {
                    ctx.flow().put(TRACKING, "T-1");
                    ctx.flow().put(WEIGHT, 3);
                    return ActionOutcome.ok();
                })
                .endTransition()
            .transition(Move.DELIVER, Spot.SHIPPED, Spot.DELIVERED)
                .guard("signed", (GuardContext<Parcel, Spot, Move> ctx) -> GuardResult.reject("nobody signed"))
                .endTransition()
            .transition(Move.HOLD, Spot.SHIPPED, Spot.HELD)
                .action("inspect", (TransitionContext<Parcel, Spot, Move> ctx) -> {
                    ctx.flow().put(NOTE, "customs");
                    throw new IllegalStateException("customs closed");
                })
                .endTransition()
            .build();

    private Parcels() { }

    /** A machine that records no trail. */
    public static StateMachine<Parcel, Spot, Move> machine() {
        return StateMachines.from(GRAPH).state(Parcel::spot, Parcel::setSpot).build();
    }

    /** A machine that records the trail of every call it starts, on {@code clock}. */
    public static StateMachine<Parcel, Spot, Move> machine(InstantSource clock) {
        return StateMachines.from(GRAPH).state(Parcel::spot, Parcel::setSpot).recordTrail(clock).build();
    }

    /** A clock whose n-th read answers n seconds after the epoch. */
    public static InstantSource ticking() {
        AtomicLong reads = new AtomicLong();
        return () -> Instant.ofEpochSecond(reads.incrementAndGet());
    }
}
