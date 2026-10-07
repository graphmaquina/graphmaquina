package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Chainable assertion over an {@link AfterCommitException}: an after-commit hook threw, and nothing is rolled back:
 * every state of the call was written. A hook runs only for a committed hop, so
 * {@link #at(MachineKey, Object, Enum, Enum, Enum)} names a hop that really happened, and {@link #records()} every
 * hop the call committed.
 */
public final class AfterCommitAssert extends BaseCallFailureAssert<AfterCommitAssert> {

    private final AfterCommitException failure;

    AfterCommitAssert(String context, AfterCommitException failure) {
        super(context, failure);
        this.failure = failure;
    }

    @Override
    protected AfterCommitAssert self() {
        return this;
    }

    public AfterCommitAssert hasHookNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "hook", expected, failure.hook());
        return this;
    }

    /** The committed hop the failing hook ran for. The entity is matched by reference. */
    public <E, S extends Enum<S>, T extends Enum<T>> AfterCommitAssert at(MachineKey<E, S, T> key, E entity, S from,
            T transition, S to) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(to, "to");
        TransitionRecord<?, ?, ?> hop = failure.hop();
        Checks.equal(context, "key", key, hop.key());
        Checks.same(context, "entity", entity, hop.entity());
        Checks.equal(context, "from", from, hop.from());
        Checks.equal(context, "transition", transition, hop.transition());
        Checks.equal(context, "to", to, hop.to());
        return this;
    }

    /** Every hop the call committed, in log order. */
    public TransitionRecordsAssert records() {
        return new TransitionRecordsAssert(context + ".records", failure.records());
    }
}
