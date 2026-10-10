package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.TransitionSpec;
import java.util.Objects;

/**
 * An entry in the log: a hop along a selected edge, or the start of a sub-machine. Both name the entity they move,
 * the state it leaves and the one it reaches, neither ever null, and the depth of the step that logged them. The
 * pending-state index, the savepoints, the rollback, the commit and the commit summary read every entry through these
 * alone; records and hooks read hops only, and starts reads starts only.
 */
sealed interface LogEntry permits LogEntry.Hop, LogEntry.Start {

    EntityRef ref();

    Enum<?> from();

    Enum<?> to();

    int depth();

    /**
     * A hop: the entity it moved and the very edge that was selected. Commit and the post-commit hooks read that
     * reference, so neither looks an edge up again by its endpoints.
     */
    record Hop(EntityRef ref, TransitionSpec<?, ?, ?> spec, int depth) implements LogEntry {

        @Override
        public Enum<?> from() { return spec.from(); }

        @Override
        public Enum<?> to() { return spec.to(); }
    }

    /**
     * The start of a sub-machine, logged right after the hop that entered {@code node}, or after the start that put
     * {@code parent} in it: the child {@code ref} moves from {@code previous}, the state it had, to {@code initial}.
     * It runs no hook. The constructor refuses a null state: only a hand-written {@code GraphDefinition} whose
     * {@code initial()} returns null could pass one.
     */
    record Start(EntityRef ref, Enum<?> previous, Enum<?> initial, EntityRef parent, Enum<?> node, int depth)
            implements LogEntry {

        public Start {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(initial, "initial");
        }

        @Override
        public Enum<?> from() { return previous; }

        @Override
        public Enum<?> to() { return initial; }
    }
}
