package io.stategraph.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Domain-level result of a transition. Technical failures are not modeled here: they throw. */
public sealed interface Outcome<E, S extends Enum<S>>
        permits Outcome.Applied, Outcome.Ignored, Outcome.Rejected, Outcome.Failed {

    E entity();

    S from();

    /** Where the entity ended up. */
    S current();

    /** ACTION or MOVE applied and committed. */
    record Applied<E, S extends Enum<S>>(E entity, S from, S to, List<TransitionRecord<?, ?>> records,
                                         List<Reaction> reactions) implements Outcome<E, S> {
        public Applied {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(records, "records");
            Objects.requireNonNull(reactions, "reactions");
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S current() {
            return to;
        }
    }

    /** STAY, a convergent no-op, or a reevaluation with no matching rule. */
    record Ignored<E, S extends Enum<S>>(E entity, S from, S to) implements Outcome<E, S> {
        public Ignored {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public S current() {
            return from;
        }
    }

    /** FORBID or a guard that rejected. Nothing ran. */
    record Rejected<E, S extends Enum<S>>(E entity, S from, S to, String reason,
                                          Optional<String> guard) implements Outcome<E, S> {
        public Rejected {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(guard, "guard");
        }

        @Override
        public S current() {
            return from;
        }
    }

    /** Domain-level failure with onFailure. Commits: the entity is at {@code movedTo}. */
    record Failed<E, S extends Enum<S>>(E entity, S from, S to, S movedTo, String reason, Optional<Throwable> cause,
                                        List<TransitionRecord<?, ?>> records,
                                        List<Reaction> reactions) implements Outcome<E, S> {
        public Failed {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(movedTo, "movedTo");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(records, "records");
            Objects.requireNonNull(reactions, "reactions");
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S current() {
            return movedTo;
        }
    }
}
