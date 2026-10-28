package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The SPI defaults: a third-party implementation keeps working, never silently drops a payload, and refuses a null one
 * instead of reading it as absent.
 */
class OperationInputDefaultsTest {
    /** The single state of the fixture machine. */
    enum S { OPEN }

    /** The single transition identifier of the fixture machine. */
    enum T { GO }

    /** A guard context that implements only its abstract methods, so {@code input()} reads the default. */
    static class PlainGuard implements GuardContext<Object, S, T> {
        public Object entity() { return this; }
        public S from() { return S.OPEN; }
        public T transition() { return T.GO; }
        public S to() { return S.OPEN; }
        public <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(MachineKey<P, Q, U> key) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(
                Class<M> type) {
            throw new UnsupportedOperationException();
        }
        public FlowView flow() {
            throw new UnsupportedOperationException();
        }
    }
    /** A transition context that implements only its abstract methods, so {@code input()} reads the default. */
    static class PlainAction implements TransitionContext<Object, S, T> {
        public Object entity() { return this; }
        public S from() { return S.OPEN; }
        public T transition() { return T.GO; }
        public S to() { return S.OPEN; }
        public int depth() { return 0; }
        public <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(MachineKey<P, Q, U> key) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(
                Class<M> type) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(
                MachineKey<P, Q, U> key, P entity, U transition) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> Outcome<P, Q, U> cascade(
                Class<M> type, P entity, U transition) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(
                MachineKey<P, Q, U> key, Collection<? extends P> entities, U transition, CascadePolicy policy) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> List<Outcome<P, Q, U>> cascadeAll(
                Class<M> type, Collection<? extends P> entities, U transition, CascadePolicy policy) {
            throw new UnsupportedOperationException();
        }
        public FlowContext flow() {
            throw new UnsupportedOperationException();
        }
    }
    /** An async machine that implements only its three abstract methods; all answer an Ignored outcome. */
    static class PlainAsync implements AsyncStateMachine<Object, S, T> {
        public CompletableFuture<Outcome<Object, S, T>> execute(Object e, T t) {
            return CompletableFuture.completedFuture(new Outcome.Ignored<>(e, S.OPEN, IgnoreReason.NO_WATCH));
        }
        public CompletableFuture<Outcome<Object, S, T>> reevaluate(Object e) { return execute(e, T.GO); }
        public CompletableFuture<Outcome<Object, S, T>> advance(Object e) { return execute(e, T.GO); }
    }
    /**
     * A machine that implements only its abstract methods, so the input overload of {@code execute} and both
     * {@code eligibility} methods are the defaults.
     */
    static class PlainMachine implements StateMachine<Object, S, T> {
        public MachineKey<Object, S, T> key() { return MachineKey.of(Object.class, S.class, T.class); }
        public GraphDefinition<Object, S, T> definition() { throw new UnsupportedOperationException(); }
        public Outcome<Object, S, T> execute(Object e, T t) {
            return new Outcome.Ignored<>(e, S.OPEN, IgnoreReason.NO_WATCH);
        }
        public Outcome<Object, S, T> reevaluate(Object e) { return execute(e, T.GO); }
        public Outcome<Object, S, T> advance(Object e) { return execute(e, T.GO); }
        public Set<T> transitionsFrom(S s) { return Set.of(); }
        public AsyncStateMachine<Object, S, T> async() { return new PlainAsync(); }
        public <R> CompletableFuture<R> supplyAsync(Supplier<R> call) {
            return CompletableFuture.completedFuture(call.get());
        }
    }
    @Test void contextsWithoutInputRemainValidAndEmpty() {
        PlainGuard guard = new PlainGuard();
        PlainAction action = new PlainAction();
        assertTrue(guard.input().isEmpty());
        assertTrue(guard.input(String.class).isEmpty());
        assertTrue(action.input().isEmpty());
        assertTrue(action.input(String.class).isEmpty());
        assertThrows(NullPointerException.class, () -> guard.input(null));
        assertThrows(NullPointerException.class, () -> action.input(null));
        assertThrows(IllegalArgumentException.class, () -> guard.input(int.class));
        assertThrows(IllegalArgumentException.class, () -> action.input(void.class));
    }
    @Test void typedReadersPreserveIdentityAndFailOnWrongType() {
        String value = new String("value");
        PlainGuard guard = new PlainGuard() { public Optional<?> input() { return Optional.of(value); } };
        PlainAction action = new PlainAction() { public Optional<?> input() { return Optional.of(value); } };
        assertSame(value, guard.input(String.class).orElseThrow());
        assertSame(value, action.input(CharSequence.class).orElseThrow());
        assertThrows(ClassCastException.class, () -> guard.input(Integer.class));
        assertThrows(ClassCastException.class, () -> action.input(Integer.class));
    }
    @Test void theSpiRefusesANullInputAndNeverDropsPayload() {
        Object e = new Object();
        PlainMachine machine = new PlainMachine();
        assertEquals("input is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.execute(e, T.GO, null)).getMessage());
        assertEquals("input is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.async().execute(e, T.GO, null)).getMessage());
        assertThrows(UnsupportedOperationException.class, () -> machine.execute(e, T.GO, "value"));
        CompletableFuture<Outcome<Object, S, T>> future = machine.async().execute(e, T.GO, "value");
        assertInstanceOf(UnsupportedOperationException.class,
                assertThrows(CompletionException.class, future::join).getCause());
    }
    @Test void eligibilityIsUnsupportedByDefault() {
        Object e = new Object();
        PlainMachine machine = new PlainMachine();
        assertEquals("this StateMachine does not support eligibility checks",
                assertThrows(UnsupportedOperationException.class, () -> machine.eligibility(e, T.GO)).getMessage());
        assertEquals("this StateMachine does not support eligibility checks",
                assertThrows(UnsupportedOperationException.class,
                        () -> machine.eligibility(e, T.GO, "value")).getMessage());
    }
    @Test void eligibilityRefusesANullArgumentBeforeAnythingElse() {
        Object e = new Object();
        PlainMachine machine = new PlainMachine();
        assertEquals("entity is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.eligibility(null, T.GO)).getMessage());
        assertEquals("transition is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.eligibility(e, null)).getMessage());
        assertEquals("entity is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.eligibility(null, T.GO, "value")).getMessage());
        assertEquals("transition is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.eligibility(e, null, "value")).getMessage());
        assertEquals("input is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> machine.eligibility(e, T.GO, null)).getMessage());
    }
}
