package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Builds machines from a {@link GraphDefinition}. */
public final class StateMachines {

    private StateMachines() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> Builder<E, S, T> from(GraphDefinition<E, S, T> definition) {
        Objects.requireNonNull(definition, "definition");
        return new Builder<>(definition);
    }

    /** Single-use and not thread-safe. */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Builder<E, S extends Enum<S>, T extends Enum<T>> {

        private final GraphDefinition<E, S, T> definition;
        private Optional<Function<? super E, ? extends S>> getter = Optional.empty();
        private Optional<BiConsumer<? super E, ? super S>> setter = Optional.empty();
        private Optional<Function<? super E, ?>> identity = Optional.empty();
        private Optional<Duration> asyncTimeout = Optional.empty();
        private Optional<Executor> executor = Optional.empty();
        private Optional<Class<? extends Machine<E, S, T>>> facade = Optional.empty();
        private boolean used;

        /** Setters must be total: one that throws breaks the atomicity of phase 2. */
        public Builder<E, S, T> state(Function<? super E, ? extends S> getter, BiConsumer<? super E, ? super S> setter) {
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(setter, "setter");
            this.getter = Optional.of(getter);
            this.setter = Optional.of(setter);
            return this;
        }

        /** Mandatory within a registry: with JPA, two instances of the same row are distinct objects. */
        public Builder<E, S, T> identity(Function<? super E, ?> identity) {
            Objects.requireNonNull(identity, "identity");
            this.identity = Optional.of(identity);
            return this;
        }

        public Builder<E, S, T> asyncTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("asyncTimeout must be positive");
            }
            this.asyncTimeout = Optional.of(timeout);
            return this;
        }

        public Builder<E, S, T> asyncExecutor(Executor executor) {
            Objects.requireNonNull(executor, "executor");
            this.executor = Optional.of(executor);
            return this;
        }

        /** The facade type that identifies this machine in {@code peer(Class)} and {@code cascade(Class, ...)}. */
        public Builder<E, S, T> as(Class<? extends Machine<E, S, T>> facade) {
            Objects.requireNonNull(facade, "facade");
            if (this.facade.isPresent()) {
                throw new IllegalStateException("the machine " + key() + " already declares as(" + this.facade.get().getSimpleName() + ")");
            }
            this.facade = Optional.of(facade);
            return this;
        }

        Optional<Class<? extends Machine<E, S, T>>> facade() { return facade; }

        /** Standalone machine: no peers, with reference identity if none was declared. */
        public StateMachine<E, S, T> build() {
            return MachineRegistry.standalone(this);
        }

        MachineKey<E, S, T> key() { return definition.key(); }

        boolean hasIdentity() { return identity.isPresent(); }

        DefaultStateMachine<E, S, T> build(MachineRegistry registry) {
            if (used) {
                throw new IllegalStateException("the builder for " + key() + " was already used");
            }
            used = true;
            if (getter.isEmpty() || setter.isEmpty()) {
                throw new IllegalStateException("the machine " + key() + " does not declare state(getter, setter)");
            }
            if (definition.hasAsync() && asyncTimeout.isEmpty()) {
                throw new IllegalStateException("the machine " + key() + " has asynchronous transitions and does not declare asyncTimeout(...)");
            }
            boolean owns = executor.isEmpty();
            Executor resolved = executor.isPresent() ? executor.get() : Executors.newVirtualThreadPerTaskExecutor();
            return new DefaultStateMachine<>(definition, getter.get(), setter.get(), identity, asyncTimeout, resolved, owns, registry);
        }
    }
}
