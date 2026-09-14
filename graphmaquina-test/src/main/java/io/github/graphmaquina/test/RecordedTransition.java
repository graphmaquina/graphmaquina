package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import java.util.Objects;
import java.util.Optional;

/**
 * One listener callback as the recorder saw it. The sequence numbers the callbacks in the order the
 * recorder observed them, starting at zero; it says nothing about the order in which concurrent
 * callbacks started. The projection is empty unless the recorder was built with one, and is the value
 * that projection returned at notification time, not a re-reading of the entity now.
 *
 * @param sequence position of this callback in the order the recorder observed
 * @param key the machine the callback was about
 * @param outcome the outcome the listener was given, exactly as published
 * @param projection the projected value captured when the callback arrived, or empty
 */
public record RecordedTransition(long sequence, MachineKey<?, ?> key, Outcome<?, ?> outcome,
        Optional<Object> projection) {

    public RecordedTransition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(projection, "projection");
    }
}
