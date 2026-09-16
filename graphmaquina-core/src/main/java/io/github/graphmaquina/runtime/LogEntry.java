package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.TransitionSpec;

/**
 * An annotated hop in the log: the entity it moved and the very edge that was selected. Commit and the
 * post-commit hooks read that reference, so neither looks an edge up again by its endpoints.
 */
record LogEntry(EntityRef ref, TransitionSpec<?, ?, ?> spec, int depth) {

    Enum<?> from() { return spec.from(); }

    Enum<?> to() { return spec.to(); }
}
