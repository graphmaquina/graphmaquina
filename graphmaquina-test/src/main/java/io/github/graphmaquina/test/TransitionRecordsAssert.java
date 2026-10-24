package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.CheckedTypes;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Chainable assertion over a list of transition records: the hops a call committed, obtained from {@link
 * BaseOutcomeAssert#records()} on any outcome view or from {@link AfterCommitAssert#records()}, or the hops a trail
 * lists, obtained from {@link TrailAssert#hops()}, which the trail of a failed call has too. One transaction may touch
 * several machines, so the list is untyped as a whole and {@link #forEntity(MachineKey, Object)} is what narrows it:
 * it selects by machine key and by entity IDENTITY, never by entity equality, so two entities that carry the same
 * business id stay distinct subjects. Order and multiplicity are part of the contract, and the list is copied when the
 * view is built, so later edits to the caller's collection are not seen, and a null element, which only a list built
 * by hand can hold, is refused then with a {@link NullPointerException}.
 *
 * <p>{@link #containsExactly(java.util.List)} compares the identifier of every hop as well as its endpoints,
 * so two edges that connect the same pair of states under different identifiers never satisfy each other.
 */
public final class TransitionRecordsAssert {

    private static final String ROOT = "transition records";

    private final String context;
    private final Optional<List<TransitionRecord<?, ?, ?>>> actual;

    /** The view of a list that came from another view, which names it as that view's own. */
    TransitionRecordsAssert(String context, Optional<? extends List<? extends TransitionRecord<?, ?, ?>>> actual) {
        this.context = context;
        this.actual = actual.map(TransitionRecordsAssert::copy);
    }

    public static TransitionRecordsAssert assertThat(List<? extends TransitionRecord<?, ?, ?>> actual) {
        return new TransitionRecordsAssert(ROOT, Optional.ofNullable(actual));
    }

    public TransitionRecordsAssert hasSize(int expected) {
        List<TransitionRecord<?, ?, ?>> records = Checks.present(context, "size", expected, actual);
        Checks.equal(context, "size", expected, records.size());
        return this;
    }

    public TransitionRecordsAssert isEmpty() {
        List<TransitionRecord<?, ?, ?>> records = Checks.present(context, "size", 0, actual);
        Checks.equal(context, "size", 0, records.size());
        return this;
    }

    /**
     * Every record in order, compared field by field: the key and the states by value, the entity by
     * reference. The first difference is reported by its own index and field name.
     */
    public TransitionRecordsAssert containsExactly(@NonNull List<? extends TransitionRecord<?, ?, ?>> expected) {
        List<? extends TransitionRecord<?, ?, ?>> wanted = List.copyOf(expected);
        List<TransitionRecord<?, ?, ?>> records = Checks.present(context, "size", wanted.size(), actual);
        Checks.equal(context, "size", wanted.size(), records.size());
        for (int index = 0; index < wanted.size(); index++) {
            String where = context + "[" + index + "]";
            compare(where, wanted.get(index), records.get(index));
        }
        return this;
    }

    /** Selects by key and by entity identity; an entity with no records yields an empty view, not a failure. */
    public <E, S extends Enum<S>, T extends Enum<T>> TypedRecordsAssert<E, S, T> forEntity(
            @NonNull MachineKey<E, S, T> key, @NonNull E entity) {
        List<TransitionRecord<?, ?, ?>> records = Checks.present(context, "entity records", entity, actual);
        List<TransitionRecord<E, S, T>> selected = records.stream()
                .filter((TransitionRecord<?, ?, ?> record) ->
                        record.key().equals(key) && record.entity() == entity)
                .map(record -> CheckedTypes.record(key, record))
                .toList();
        return new TypedRecordsAssert<>(context + " for " + key, selected);
    }

    private static List<TransitionRecord<?, ?, ?>> copy(List<? extends TransitionRecord<?, ?, ?>> records) {
        return List.copyOf(records);
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
