package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * A {@link MachineObserver} that keeps the outcome of every call that completed, so a test can assert about them
 * after the fact. It records completed calls and nothing else: a call that throws is not recorded, and a watch
 * reaction is a call of its own, so the recorder never walks {@code reactions()} and what it holds is the sequence of
 * completed calls, not a complete trace of the engine's work.
 *
 * <p>An entity is mutable and keeps moving after its call completed, so an outcome read later may no longer
 * describe the entity that produced it. That is what a projection is for: {@link
 * #projecting(BiFunction)} runs the given function when the call completes and stores what it returned. The stored
 * value is only as stable as what the function chose to return — a copy of the values the test cares about,
 * typically a state or an id. Returning the live entity stores a reference that keeps changing; no deep
 * immutability is promised for an arbitrary object.
 *
 * <p>A registry accepts any number of observers, so the recorder sits beside the test's own.
 */
public final class TransitionRecorder implements MachineObserver {

    private final Optional<BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?>> projector;
    private final List<RecordedTransition> events = new ArrayList<>();
    private long nextSequence;

    private TransitionRecorder(Optional<BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?>> projector) {
        this.projector = projector;
    }

    /** A recorder that keeps the completed calls as published, with no projection. */
    public static TransitionRecorder create() {
        return new TransitionRecorder(Optional.empty());
    }

    /**
     * The projection runs on the thread of the call that completed and must return a value. A null projector is
     * refused here; a projection that returns null makes the recorder throw, which the registry logs and does not
     * pass on, and that call is not recorded.
     */
    public static TransitionRecorder projecting(BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?> projector) {
        Objects.requireNonNull(projector, "projector");
        return new TransitionRecorder(Optional.of(projector));
    }

    @Override
    public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(elapsed, "elapsed");
        MachineKey<?, ?, ?> key = call.key();
        Optional<Object> captured = projector.map(
                (BiFunction<MachineKey<?, ?, ?>, Outcome<?, ?, ?>, ?> project) ->
                        Objects.requireNonNull(project.apply(key, outcome), "projection"));
        synchronized (events) {
            events.add(new RecordedTransition(nextSequence++, key, outcome, captured));
        }
    }

    /** The calls recorded so far, copied, so later calls do not change a list already handed out. */
    public List<RecordedTransition> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public RecordedTransitionsAssert assertRecorded() {
        return new RecordedTransitionsAssert(snapshot());
    }
}
