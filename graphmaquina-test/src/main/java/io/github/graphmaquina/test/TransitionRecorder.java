package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * A {@link TransitionListener} that keeps every callback it receives, so a test can assert about them
 * after the fact. It records callbacks and nothing else: the engine notifies a reaction in its own call,
 * so the recorder never walks {@code reactions()} and what it holds is the sequence of notifications,
 * not a complete trace of the engine's work.
 *
 * <p>An entity is mutable and keeps moving after its callback, so an outcome read later may no longer
 * describe the entity that produced it. That is what a projection is for: {@link
 * #projecting(BiFunction)} runs the given function when the callback arrives and stores what it
 * returned. The stored value is only as stable as what the function chose to return — a copy of the
 * values the test cares about, typically a state or an id. Returning the live entity stores a reference
 * that keeps changing; no deep immutability is promised for an arbitrary object.
 *
 * <p>A registry accepts a single listener, so {@link #compose(TransitionListener...)} is how a recorder
 * shares that slot with the test's own listener.
 */
public final class TransitionRecorder implements TransitionListener {

    private final Optional<BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?>> projector;
    private final List<RecordedTransition> events = new ArrayList<>();
    private long nextSequence;

    private TransitionRecorder(Optional<BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?>> projector) {
        this.projector = projector;
    }

    /** A recorder that keeps the callbacks as published, with no projection. */
    public static TransitionRecorder create() {
        return new TransitionRecorder(Optional.empty());
    }

    /** The projection runs on the notifying thread and must return a value; a null projection is rejected. */
    public static TransitionRecorder projecting(BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?> projector) {
        Objects.requireNonNull(projector, "projector");
        return new TransitionRecorder(Optional.of(projector));
    }

    @Override
    public void onOutcome(MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(outcome, "outcome");
        Optional<Object> captured =
                projector.map(p -> Objects.requireNonNull(p.apply(key, outcome), "projection"));
        synchronized (events) {
            events.add(new RecordedTransition(nextSequence++, key, outcome, captured));
        }
    }

    /** The callbacks recorded so far, copied, so later callbacks do not change a list already handed out. */
    public List<RecordedTransition> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public RecordedTransitionsAssert assertRecorded() {
        return new RecordedTransitionsAssert(snapshot());
    }

    /**
     * One listener that calls the given ones in order. A listener that throws stops the chain there and
     * its failure reaches the engine untouched, so the listeners after it are not called.
     */
    public static TransitionListener compose(TransitionListener... listeners) {
        List<TransitionListener> chain = List.of(listeners);
        return (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> {
            for (TransitionListener listener : chain) {
                listener.onOutcome(key, outcome);
            }
        };
    }
}
