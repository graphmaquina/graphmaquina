package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The states a chosen set of entities was in at one moment, and what changed since. Tracking is
 * explicit: the snapshot reports the entities it was given and no others, so an entity the test does
 * not name can move without ever failing {@link #assertUnchanged()}. {@link Builder#capture()} freezes
 * both the membership and the values, so tracking more entities afterwards leaves an existing snapshot
 * alone, while {@link #changes()} reads every tracked getter again on each call and therefore answers
 * about the entities as they are now.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class StateSnapshot {

    private static final String CONTEXT = "state snapshot";

    private final List<Captured> captured;

    public static Builder builder() {
        return new Builder();
    }

    /** Reads every tracked getter once, in tracking order, and keeps only the entities that moved. */
    public List<StateChange> changes() {
        List<StateChange> changes = new ArrayList<>();
        for (Captured entry : captured) {
            Optional<Enum<?>> after = entry.tracked().reader().get();
            if (!entry.before().equals(after)) {
                changes.add(new StateChange(entry.tracked().key(), entry.tracked().entity(),
                        entry.before(), after));
            }
        }
        return List.copyOf(changes);
    }

    /** Fails with every entity that moved, not only the first, so one run names the whole difference. */
    public void assertUnchanged() {
        List<StateChange> changes = changes();
        if (!changes.isEmpty()) {
            Checks.fail(CONTEXT, "changes", List.of(), changes);
        }
    }

    /**
     * Collects the entities to track before a snapshot is taken. The same entity may be tracked under
     * several machines, since one entity class belongs to as many machines as it has state enums, but
     * tracking it twice under the same machine is a configuration error: the second entry could only
     * repeat the first.
     */
    public static final class Builder {

        private final List<Tracked> tracked = new ArrayList<>();

        private Builder() { }

        public <E, S extends Enum<S>, T extends Enum<T>> Builder track(
                @NonNull MachineKey<E, S, T> key, @NonNull E entity, @NonNull Function<? super E, ? extends S> getter) {
            for (Tracked existing : tracked) {
                if (existing.key().equals(key) && existing.entity() == entity) {
                    throw new IllegalArgumentException("the entity is already tracked under " + key);
                }
            }
            tracked.add(new Tracked(key, entity, () -> Optional.ofNullable(getter.apply(entity))));
            return this;
        }

        /** Reads every getter once, in tracking order; the snapshot is independent of this builder afterwards. */
        public StateSnapshot capture() {
            List<Captured> entries = new ArrayList<>();
            for (Tracked entry : tracked) {
                entries.add(new Captured(entry, entry.reader().get()));
            }
            return new StateSnapshot(List.copyOf(entries));
        }
    }

    /**
     * An entity tracked under a machine, with the reader that gets its state through the tracking getter, empty when
     * the getter answers null.
     */
    private record Tracked(MachineKey<?, ?, ?> key, Object entity, Supplier<Optional<Enum<?>>> reader) { }

    /** A tracked entity together with the state its reader returned when {@link Builder#capture()} froze it. */
    private record Captured(Tracked tracked, Optional<Enum<?>> before) { }
}
