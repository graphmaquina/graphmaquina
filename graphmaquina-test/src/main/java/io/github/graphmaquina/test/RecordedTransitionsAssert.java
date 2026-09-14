package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.CheckedTypes;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;

/**
 * Chainable assertion over the callbacks a {@link TransitionRecorder} observed, obtained from
 * {@link TransitionRecorder#assertRecorded()}. One registry notifies every machine it holds, so the list
 * is untyped as a whole and {@link #forEntity(MachineKey, Object)} is what narrows it: it selects by
 * machine key and by entity IDENTITY, never by entity equality. Order and multiplicity are part of the
 * contract, and the list was copied out of the recorder when the view was built, so callbacks that
 * arrive afterwards are not seen by this view.
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

    /** The machine of every callback in order, which is how a test pins which machines were notified. */
    public RecordedTransitionsAssert hasKeysExactly(MachineKey<?, ?>... expected) {
        List<MachineKey<?, ?>> wanted = List.of(expected);
        Checks.equal(CONTEXT, "size", wanted.size(), actual.size());
        for (int index = 0; index < wanted.size(); index++) {
            Checks.equal(CONTEXT + "[" + index + "]", "key", wanted.get(index), actual.get(index).key());
        }
        return this;
    }

    /**
     * The variant of every callback in order, compared as the outcome's own class: {@code
     * Outcome.Applied.class} matches an applied outcome and no other, and a supertype matches nothing.
     */
    public RecordedTransitionsAssert hasOutcomeKindsExactly(Class<?>... expected) {
        List<Class<?>> wanted = List.of(expected);
        Checks.equal(CONTEXT, "size", wanted.size(), actual.size());
        for (int index = 0; index < wanted.size(); index++) {
            Checks.equal(CONTEXT + "[" + index + "]", "kind", wanted.get(index),
                    actual.get(index).outcome().getClass());
        }
        return this;
    }

    /** Selects by key and by entity identity; an entity with no callbacks yields an empty view, not a failure. */
    public <E, S extends Enum<S>> OutcomeSequenceAssert<E, S> forEntity(MachineKey<E, S> key, E entity) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        List<Outcome<E, S>> selected = actual.stream()
                .filter(recorded -> recorded.key().equals(key) && recorded.outcome().entity() == entity)
                .map(recorded -> CheckedTypes.outcome(key, recorded.outcome()))
                .toList();
        return new OutcomeSequenceAssert<>(CONTEXT + " for " + key, selected);
    }
}
