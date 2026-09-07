package io.stategraph.runtime;

import io.stategraph.api.EdgeKind;
import java.util.Optional;

/** An annotated hop in the log. convergent marks the idempotent no-op of a cascade. */
record LogEntry(EntityRef ref, Enum<?> from, Enum<?> to, EdgeKind kind, Optional<String> action, int depth, boolean convergent) { }
