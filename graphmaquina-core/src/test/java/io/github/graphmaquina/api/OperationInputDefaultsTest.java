package io.github.graphmaquina.api;

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

class OperationInputDefaultsTest {
    enum S { OPEN }

    static class OldGuard implements GuardContext<Object, S> {
        public Object entity() { return this; }
        public S from() { return S.OPEN; }
        public S to() { return S.OPEN; }
        public <P, Q extends Enum<Q>> PeerView<P, Q> peer(MachineKey<P, Q> key) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(Class<M> type) {
            throw new UnsupportedOperationException();
        }
    }
    static class OldAction implements TransitionContext<Object, S> {
        public Object entity() { return this; }
        public S from() { return S.OPEN; }
        public S to() { return S.OPEN; }
        public int depth() { return 0; }
        public <P, Q extends Enum<Q>> PeerView<P, Q> peer(MachineKey<P, Q> key) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(Class<M> type) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>> Outcome<P, Q> cascade(MachineKey<P, Q> key, P entity, Q target) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, M extends Machine<P, Q>> Outcome<P, Q> cascade(Class<M> type, P entity, Q target) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>> List<Outcome<P, Q>> cascadeAll(
                MachineKey<P, Q> key, Collection<? extends P> entities, Q target, CascadePolicy policy) {
            throw new UnsupportedOperationException();
        }
        public <P, Q extends Enum<Q>, M extends Machine<P, Q>> List<Outcome<P, Q>> cascadeAll(
                Class<M> type, Collection<? extends P> entities, Q target, CascadePolicy policy) {
            throw new UnsupportedOperationException();
        }
    }
    static class OldAsync implements AsyncStateMachine<Object, S> {
        public CompletableFuture<Outcome<Object, S>> transitionTo(Object e, S s) {
            return CompletableFuture.completedFuture(new Outcome.Ignored<>(e, s, s));
        }
        public CompletableFuture<Outcome<Object, S>> reevaluate(Object e) { return transitionTo(e, S.OPEN); }
    }
    static class OldMachine implements StateMachine<Object, S> {
        public MachineKey<Object, S> key() { return MachineKey.of(Object.class, S.class); }
        public GraphDefinition<Object, S> definition() { throw new UnsupportedOperationException(); }
        public Outcome<Object, S> transitionTo(Object e, S s) { return new Outcome.Ignored<>(e, s, s); }
        public Outcome<Object, S> reevaluate(Object e) { return transitionTo(e, S.OPEN); }
        public Set<S> allowedFrom(S s) { return Set.of(); }
        public AsyncStateMachine<Object, S> async() { return new OldAsync(); }
        public <T> CompletableFuture<T> supplyAsync(Supplier<T> call) {
            return CompletableFuture.completedFuture(call.get());
        }
    }
    @Test void oldContextsRemainValidAndEmpty() {
        OldGuard guard = new OldGuard();
        OldAction action = new OldAction();
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
        OldGuard guard = new OldGuard() { public Optional<?> input() { return Optional.of(value); } };
        OldAction action = new OldAction() { public Optional<?> input() { return Optional.of(value); } };
        assertSame(value, guard.input(String.class).orElseThrow());
        assertSame(value, action.input(CharSequence.class).orElseThrow());
        assertThrows(ClassCastException.class, () -> guard.input(Integer.class));
        assertThrows(ClassCastException.class, () -> action.input(Integer.class));
    }
    @Test void legacySpiDelegatesAbsenceButNeverDropsPayload() {
        Object e = new Object();
        OldMachine machine = new OldMachine();
        assertSame(e, machine.transitionTo(e, S.OPEN, null).entity());
        assertSame(e, machine.async().transitionTo(e, S.OPEN, null).join().entity());
        assertThrows(UnsupportedOperationException.class, () -> machine.transitionTo(e, S.OPEN, "value"));
        CompletableFuture<Outcome<Object, S>> future = machine.async().transitionTo(e, S.OPEN, "value");
        assertInstanceOf(UnsupportedOperationException.class,
                assertThrows(CompletionException.class, future::join).getCause());
    }
}
