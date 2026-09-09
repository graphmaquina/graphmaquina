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

    public static <E, S extends Enum<S>> Builder<E, S> from(GraphDefinition<E, S> definition) {
        Objects.requireNonNull(definition, "definition");
        return new Builder<>(definition);
    }

    /** Single-use and not thread-safe. */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Builder<E, S extends Enum<S>> {

        private final GraphDefinition<E, S> definition;
        private Optional<Function<? super E, ? extends S>> getter = Optional.empty();
        private Optional<BiConsumer<? super E, ? super S>> setter = Optional.empty();
        private Optional<Function<? super E, ?>> identity = Optional.empty();
        private Optional<Duration> asyncTimeout = Optional.empty();
        private Optional<Executor> executor = Optional.empty();
        private Optional<Class<? extends Machine<E, S>>> facade = Optional.empty();
        private boolean used;

        /** Setters must be total: one that throws breaks the atomicity of phase 2. */
        public Builder<E, S> state(Function<? super E, ? extends S> getter, BiConsumer<? super E, ? super S> setter) {
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(setter, "setter");
            this.getter = Optional.of(getter);
            this.setter = Optional.of(setter);
            return this;
        }

        /** Mandatory within a registry: with JPA, two instances of the same row are distinct objects. */
        public Builder<E, S> identity(Function<? super E, ?> identity) {
            Objects.requireNonNull(identity, "identity");
            this.identity = Optional.of(identity);
            return this;
        }

        public Builder<E, S> asyncTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("asyncTimeout must be positive");
            }
            this.asyncTimeout = Optional.of(timeout);
            return this;
        }

        public Builder<E, S> asyncExecutor(Executor executor) {
            Objects.requireNonNull(executor, "executor");
            this.executor = Optional.of(executor);
            return this;
        }

        /** The facade type that identifies this machine in {@code peer(Class)} and {@code cascade(Class, ...)}. */
        public Builder<E, S> as(Class<? extends Machine<E, S>> facade) {
            Objects.requireNonNull(facade, "facade");
            if (this.facade.isPresent()) {
                throw new IllegalStateException("the machine " + key() + " already declares as(" + this.facade.get().getSimpleName() + ")");
            }
            this.facade = Optional.of(facade);
            return this;
        }

        Optional<Class<? extends Machine<E, S>>> facade() { return facade; }

        /** Standalone machine: no peers, with reference identity if none was declared. */
        public StateMachine<E, S> build() {
            return MachineRegistry.standalone(this);
        }

        MachineKey<E, S> key() { return definition.key(); }

        boolean hasIdentity() { return identity.isPresent(); }

        DefaultStateMachine<E, S> build(MachineRegistry registry) {
            if (used) {
                throw new IllegalStateException("the builder for " + key() + " was already used");
            }
            used = true;
            if (getter.isEmpty() || setter.isEmpty()) {
                throw new IllegalStateException("the machine " + key() + " does not declare state(getter, setter)");
            }
            if (definition.hasAsync() && asyncTimeout.isEmpty()) {
                throw new IllegalStateException("the machine " + key() + " has asynchronous cells and does not declare asyncTimeout(...)");
            }
            boolean owns = executor.isEmpty();
            Executor resolved = executor.isPresent() ? executor.get() : Executors.newVirtualThreadPerTaskExecutor();
            return new DefaultStateMachine<>(definition, getter.get(), setter.get(), identity, asyncTimeout, resolved, owns, registry);
        }
    }
}
