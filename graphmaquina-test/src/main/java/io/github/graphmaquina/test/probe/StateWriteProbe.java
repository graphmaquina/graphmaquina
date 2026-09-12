package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * The setter a machine writes its state with, wrapped so that a test can watch phase 2 and make one write
 * fail. It is a {@link BiConsumer} and goes straight into {@code StateMachines.from(graph).state(getter,
 * probe)}; the same probe can be given to several machines, and then the call ordinals count the writes of
 * the whole commit in the order the engine made them.
 *
 * <p>Every call is numbered as it is entered, and {@link #failOnCall} arms the failure of exactly one of
 * those numbers: that call throws the given exception instance and never reaches the wrapped setter, so the
 * entity keeps the state it had and the engine reports it as pending. A call is recorded as soon as it is
 * entered, as {@link WriteStatus#RUNNING}, and that same record is settled to {@code RETURNED} or
 * {@code THREW} when the call leaves; so {@link #writes()} is complete and in entry order at every instant,
 * and a call still inside a blocked setter is listed in its own place rather than missing from the list.
 *
 * <p>The wrapped setter is invoked outside every lock this class takes, so a setter that blocks holds up
 * nothing but its own caller. Configuration is meant to finish before the probe reaches a machine.
 *
 * @param <E> the entity type of the machine whose setter is wrapped
 * @param <S> the state enum of that machine
 */
public final class StateWriteProbe<E, S extends Enum<S>> implements BiConsumer<E, S> {

    private final BiConsumer<? super E, ? super S> delegate;
    private final Map<Integer, RuntimeException> failures = new ConcurrentHashMap<>();
    private final NavigableMap<Integer, StateWrite<E, S>> recorded = new ConcurrentSkipListMap<>();
    private final AtomicInteger entered = new AtomicInteger();

    private StateWriteProbe(BiConsumer<? super E, ? super S> delegate) {
        this.delegate = delegate;
    }

    public static <E, S extends Enum<S>> StateWriteProbe<E, S> wrapping(
            BiConsumer<? super E, ? super S> delegate) {
        return new StateWriteProbe<E, S>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * Arms the failure of one call, counting from 1. That call throws this very instance instead of reaching
     * the wrapped setter. Arming the same call twice is a fixture error rather than a replacement, since a
     * silent replacement would hide one of the two exceptions the test meant to observe.
     */
    public StateWriteProbe<E, S> failOnCall(int oneBased, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (oneBased < 1) {
            throw new IllegalArgumentException("the call ordinal counts from 1, not " + oneBased);
        }
        RuntimeException armed = failures.putIfAbsent(oneBased, failure);
        if (armed != null) {
            throw new IllegalStateException("call " + oneBased + " already fails with " + armed);
        }
        return this;
    }

    /** The calls recorded so far, copied, so a later call does not change a list already handed out. */
    public List<StateWrite<E, S>> writes() {
        return List.copyOf(recorded.values());
    }

    /**
     * Expects exactly this many calls, raising {@link GraphAssertionError} otherwise, and returns the probe
     * so writes keep chaining. {@link ProbeCalls#assertCount(int)} deliberately returns nothing instead.
     */
    public StateWriteProbe<E, S> assertCount(int expected) {
        List<StateWrite<E, S>> writes = writes();
        if (writes.size() != expected) {
            Checks.fail("state writes", "count", expected, writes.size());
        }
        return this;
    }

    @Override
    public void accept(E entity, S requested) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(requested, "requested");
        int ordinal = entered.incrementAndGet();
        recorded.put(ordinal, new StateWrite<>(entity, requested, WriteStatus.RUNNING, Optional.empty()));
        RuntimeException armed = failures.get(ordinal);
        WriteStatus status = WriteStatus.THREW;
        RuntimeException thrown = null;
        try {
            if (armed != null) {
                throw armed;
            }
            delegate.accept(entity, requested);
            status = WriteStatus.RETURNED;
        } catch (RuntimeException failure) {
            thrown = failure;
            throw failure;
        } finally {
            recorded.put(ordinal, new StateWrite<>(entity, requested, status, Optional.ofNullable(thrown)));
        }
    }
}
