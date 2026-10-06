package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.CheckedTypes;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Chainable assertion over the transition records a committed outcome carries, obtained from
 * {@link AppliedOutcomeAssert#records()} or {@link FailedOutcomeAssert#records()}. One transaction may touch
 * several machines, so the list is untyped as a whole and {@link #forEntity(MachineKey, Object)} is what
 * narrows it: it selects by machine key and by entity IDENTITY, never by entity equality, so two entities
 * that carry the same business id stay distinct subjects. Order and multiplicity are part of the contract,
 * and the list is copied when the view is built, so later edits to the caller's collection are not seen.
 * A null element, which only a subject built by hand can hold, is selected by no entity and is reported by
 * position when {@link #containsExactly(java.util.List)} compares it.
 *
 * <p>{@link #containsExactly(java.util.List)} compares the identifier of every hop as well as its endpoints,
 * so two edges that connect the same pair of states under different identifiers never satisfy each other.
 */
public final class TransitionRecordsAssert {

    private static final String ROOT = "transition records";

    private final String context;
    private final List<? extends TransitionRecord<?, ?, ?>> actual;

    /** The view of a list that came from another view, which names it as that view's own. */
    TransitionRecordsAssert(String context, List<? extends TransitionRecord<?, ?, ?>> actual) {
        this.context = context;
        this.actual = actual == null ? null : Collections.unmodifiableList(new ArrayList<>(actual));
    }

    public static TransitionRecordsAssert assertThat(List<? extends TransitionRecord<?, ?, ?>> actual) {
        return new TransitionRecordsAssert(ROOT, actual);
    }

    public TransitionRecordsAssert hasSize(int expected) {
        List<? extends TransitionRecord<?, ?, ?>> records = Checks.subject(context, "size", expected, actual);
        Checks.equal(context, "size", expected, records.size());
        return this;
    }

    public TransitionRecordsAssert isEmpty() {
        List<? extends TransitionRecord<?, ?, ?>> records = Checks.subject(context, "size", 0, actual);
        Checks.equal(context, "size", 0, records.size());
        return this;
    }

    /**
     * Every record in order, compared field by field: the key and the states by value, the entity by
     * reference. The first difference is reported by its own index and field name.
     */
    public TransitionRecordsAssert containsExactly(List<? extends TransitionRecord<?, ?, ?>> expected) {
        Objects.requireNonNull(expected, "expected");
        List<? extends TransitionRecord<?, ?, ?>> wanted = List.copyOf(expected);
        List<? extends TransitionRecord<?, ?, ?>> records =
                Checks.subject(context, "size", wanted.size(), actual);
        Checks.equal(context, "size", wanted.size(), records.size());
        for (int index = 0; index < wanted.size(); index++) {
            String where = context + "[" + index + "]";
            compare(where, wanted.get(index), Checks.subject(where, "record", wanted.get(index), records.get(index)));
        }
        return this;
    }

    /** Selects by key and by entity identity; an entity with no records yields an empty view, not a failure. */
    public <E, S extends Enum<S>, T extends Enum<T>> TypedRecordsAssert<E, S, T> forEntity(
            MachineKey<E, S, T> key, E entity) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        List<? extends TransitionRecord<?, ?, ?>> records =
                Checks.subject(context, "entity records", entity, actual);
        List<TransitionRecord<E, S, T>> selected = records.stream()
                .filter((TransitionRecord<?, ?, ?> record) ->
                        record != null && record.key().equals(key) && record.entity() == entity)
                .map(record -> CheckedTypes.record(key, record))
                .toList();
        return new TypedRecordsAssert<>(context + " for " + key, selected);
    }

    private static void compare(String where, TransitionRecord<?, ?, ?> expected,
            TransitionRecord<?, ?, ?> actual) {
        Checks.equal(where, "key", expected.key(), actual.key());
        Checks.same(where, "entity", expected.entity(), actual.entity());
        Checks.equal(where, "from", expected.from(), actual.from());
        Checks.equal(where, "transition", expected.transition(), actual.transition());
        Checks.equal(where, "to", expected.to(), actual.to());
        Checks.equal(where, "kind", expected.kind(), actual.kind());
        Checks.equal(where, "action", expected.action(), actual.action());
        Checks.equal(where, "depth", expected.depth(), actual.depth());
    }
}
