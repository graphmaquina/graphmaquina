package io.github.graphmaquina.test;

import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.function.Function;

/**
 * Chainable, typed assertion over the state an entity is observed to be in, independently of any
 * outcome that may have produced it. The getter is applied when the expectation runs, so the same view
 * re-read after a further transition reports the new state.
 *
 * @param <E> entity type
 * @param <S> state enum of the entity
 */
public final class EntityStateAssert<E, S extends Enum<S>> {

    private final E actual;
    private final Function<? super E, ? extends S> getter;

    private EntityStateAssert(E actual, Function<? super E, ? extends S> getter) {
        this.actual = actual;
        this.getter = getter;
    }

    public static <E, S extends Enum<S>> EntityStateAssert<E, S> assertThat(
            E actual, Function<? super E, ? extends S> getter) {
        Objects.requireNonNull(getter, "getter");
        return new EntityStateAssert<>(actual, getter);
    }

    public EntityStateAssert<E, S> isIn(S expected) {
        Objects.requireNonNull(expected, "expected");
        E entity = Checks.subject("entity", "state", expected, actual);
        Checks.equal("entity", "state", expected, getter.apply(entity));
        return this;
    }
}
