package io.github.graphmaquina.api;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** Domain-level result of a transition. Technical failures are not modeled here: they throw. */
public sealed interface Outcome<E, S extends Enum<S>>
        permits Outcome.Applied, Outcome.Ignored, Outcome.Rejected, Outcome.Failed {

    E entity();

    S from();

    /** Where the entity ended up. */
    S current();

    /** ACTION or MOVE applied and committed. */
    record Applied<E, S extends Enum<S>>(@NonNull E entity, @NonNull S from, @NonNull S to,
                                         @NonNull List<TransitionRecord<?, ?>> records,
                                         @NonNull List<Reaction> reactions) implements Outcome<E, S> {
        public Applied {
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S current() {
            return to;
        }
    }

    /** STAY, a convergent no-op, or a reevaluation with no matching rule. */
    record Ignored<E, S extends Enum<S>>(@NonNull E entity, @NonNull S from, @NonNull S to) implements Outcome<E, S> {
        @Override
        public S current() {
            return from;
        }
    }

    /** FORBID or a guard that rejected. Nothing ran. */
    record Rejected<E, S extends Enum<S>>(@NonNull E entity, @NonNull S from, @NonNull S to, @NonNull String reason,
                                          @NonNull Optional<String> guard) implements Outcome<E, S> {
        @Override
        public S current() {
            return from;
        }
    }

    /** Domain-level failure with onFailure. Commits: the entity is at {@code movedTo}. */
    record Failed<E, S extends Enum<S>>(@NonNull E entity, @NonNull S from, @NonNull S to, @NonNull S movedTo,
                                        @NonNull String reason, @NonNull Optional<Throwable> cause,
                                        @NonNull List<TransitionRecord<?, ?>> records,
                                        @NonNull List<Reaction> reactions) implements Outcome<E, S> {
        public Failed {
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S current() {
            return movedTo;
        }
    }
}
