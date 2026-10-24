package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import java.util.Optional;
import lombok.NonNull;

/**
 * One completed call as the recorder saw it. The sequence numbers the calls in the order the
 * recorder saw them complete, starting at zero; it says nothing about the order in which concurrent
 * calls started. The projection is empty unless the recorder was built with one, and is the value
 * that projection returned when the call completed, not a re-reading of the entity now.
 *
 * @param sequence position of this call in the order the recorder saw the calls complete
 * @param key the machine of the call
 * @param outcome the outcome the call returned, exactly as its observers received it
 * @param projection the projected value captured when the call completed, or empty
 */
public record RecordedTransition(long sequence, @NonNull MachineKey<?, ?, ?> key, @NonNull Outcome<?, ?, ?> outcome,
        @NonNull Optional<Object> projection) {

    public RecordedTransition {
    }
}
