package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Chainable assertion over an {@link AfterCommitException}: an after-commit hook threw, and nothing is rolled back.
 * A hook runs only for a committed hop, once every state was written, so {@link #at(MachineKey, Object, Enum, Enum)}
 * names a hop that really happened. The step is the hook's name. The exception carries only the endpoints, so the
 * identifier of the committed edge is read from the records rather than here.
 */
public final class AfterCommitAssert extends BaseFailureAssert<AfterCommitAssert> {

    private final AfterCommitException failure;

    AfterCommitAssert(String context, AfterCommitException failure) {
        super(context, failure);
        this.failure = failure;
    }

    @Override
    protected AfterCommitAssert self() {
        return this;
    }

    public AfterCommitAssert hasStepNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "step", expected, failure.step());
        return this;
    }

    /**
     * The endpoints of the committed hop the failing hook ran for. The entity is matched by reference.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> AfterCommitAssert at(MachineKey<E, S, T> key, E entity,
            S from, S to) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Checks.equal(context, "key", key, failure.key());
        Checks.same(context, "entity", entity, failure.entity());
        Checks.equal(context, "from", from, failure.from());
        Checks.equal(context, "to", to, failure.to());
        return this;
    }
}
