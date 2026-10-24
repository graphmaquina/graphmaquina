package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.test.internal.CheckedTypes;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import lombok.NonNull;

/**
 * Chainable assertion over the completed calls a {@link TransitionRecorder} recorded, obtained from
 * {@link TransitionRecorder#assertRecorded()}. One registry's observers see every machine it holds, so the list
 * is untyped as a whole and {@link #forEntity(MachineKey, Object)} is what narrows it: it selects by
 * machine key and by entity IDENTITY, never by entity equality. Order and multiplicity are part of the
 * contract, and the list was copied out of the recorder when the view was built, so calls that
 * complete afterwards are not seen by this view.
 */
public final class RecordedTransitionsAssert {

    private static final String CONTEXT = "recorded transitions";

    private final List<RecordedTransition> actual;

    RecordedTransitionsAssert(List<RecordedTransition> actual) {
        this.actual = actual;
    }

    public RecordedTransitionsAssert hasSize(int expected) {
        Checks.equal(CONTEXT, "size", expected, actual.size());
        return this;
    }

    /** The machine of every completed call in order, which is how a test pins which machines were notified. */
    public RecordedTransitionsAssert hasKeysExactly(MachineKey<?, ?, ?>... expected) {
        List<MachineKey<?, ?, ?>> wanted = List.of(expected);
        Checks.equal(CONTEXT, "size", wanted.size(), actual.size());
        for (int index = 0; index < wanted.size(); index++) {
            Checks.equal(CONTEXT + "[" + index + "]", "key", wanted.get(index), actual.get(index).key());
        }
        return this;
    }

    /**
     * The status of every completed call in order, one {@link OutcomeStatus} per call, which pins a
     * sequence of applied, rejected, failed and ignored results without naming the variant classes.
     */
    public RecordedTransitionsAssert hasStatusesExactly(OutcomeStatus... expected) {
        List<OutcomeStatus> wanted = List.of(expected);
        Checks.equal(CONTEXT, "size", wanted.size(), actual.size());
        for (int index = 0; index < wanted.size(); index++) {
            Checks.equal(CONTEXT + "[" + index + "]", "status", wanted.get(index),
                    actual.get(index).outcome().status());
        }
        return this;
    }

    /** Selects by key and by entity identity; an entity with no calls yields an empty view, not a failure. */
    public <E, S extends Enum<S>, T extends Enum<T>> OutcomeSequenceAssert<E, S, T> forEntity(
            @NonNull MachineKey<E, S, T> key, @NonNull E entity) {
        List<Outcome<E, S, T>> selected = actual.stream()
                .filter(recorded -> recorded.key().equals(key) && recorded.outcome().entity() == entity)
                .map(recorded -> CheckedTypes.outcome(key, recorded.outcome()))
                .toList();
        return new OutcomeSequenceAssert<>(CONTEXT + " for " + key, selected);
    }
}
