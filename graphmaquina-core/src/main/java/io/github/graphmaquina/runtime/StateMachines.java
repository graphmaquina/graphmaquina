package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import java.time.Duration;
import java.util.List;
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

    /**
     * Single-use and not thread-safe. Once a build that uses it succeeds, standalone or within a registry, every
     * method throws IllegalStateException instead of accepting configuration nothing would read. A build that fails
     * consumes nothing: fix what it reports and build again.
     */
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

        /**
         * Setters must be total: one that throws a throwable that is not an Error breaks the atomicity of phase 2 and
         * ends the call with a {@code CommitFailedException}.
         */
        public Builder<E, S, T> state(Function<? super E, ? extends S> getter,
                                      BiConsumer<? super E, ? super S> setter) {
            requireUnused();
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(setter, "setter");
            this.getter = Optional.of(getter);
            this.setter = Optional.of(setter);
            return this;
        }

        /** Mandatory within a registry: with JPA, two instances of the same row are distinct objects. */
        public Builder<E, S, T> identity(Function<? super E, ?> identity) {
            requireUnused();
            Objects.requireNonNull(identity, "identity");
            this.identity = Optional.of(identity);
            return this;
        }

        /**
         * The wait budget of every root transaction this machine starts, counted from the moment the root begins
         * executing. It bounds the waits of the root's asynchronous guards and actions, and those of every peer the
         * root cascades to: inside a cascade, the peer waits under this root's budget, never under its own
         * asyncTimeout. An edge's own timeout can only shorten it. Any positive duration is accepted, and one of
         * {@code Duration.ofNanos(Long.MAX_VALUE)} or more means no limit. Mandatory for a machine with asynchronous
         * transitions; a machine without them may leave it out, and then its roots have no deadline: the asynchronous
         * waits of the peers they cascade to are unbounded, whatever those peers' own asyncTimeout, and only an edge
         * that declares a timeout of its own still bounds its action's wait.
         */
        public Builder<E, S, T> asyncTimeout(Duration timeout) {
            requireUnused();
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("asyncTimeout must be positive");
            }
            this.asyncTimeout = Optional.of(timeout);
            return this;
        }

        public Builder<E, S, T> asyncExecutor(Executor executor) {
            requireUnused();
            Objects.requireNonNull(executor, "executor");
            this.executor = Optional.of(executor);
            return this;
        }

        /** The facade type that identifies this machine in {@code peer(Class)} and {@code cascade(Class, ...)}. */
        public Builder<E, S, T> as(Class<? extends Machine<E, S, T>> facade) {
            requireUnused();
            Objects.requireNonNull(facade, "facade");
            if (this.facade.isPresent()) {
                throw new IllegalStateException(
                        "the machine " + key() + " already declares as(" + this.facade.get().getSimpleName() + ")");
            }
            this.facade = Optional.of(facade);
            return this;
        }

        Optional<Class<? extends Machine<E, S, T>>> facade() { return facade; }

        /**
         * Standalone machine: a registry of one, built by the same code as {@link MachineRegistry.Builder#build()},
         * but with no observer, since only {@link MachineRegistry.Builder#observer} adds one. Its own facade from
         * {@code as(...)} and its own key are the only peers it reaches, with reference identity if none was
         * declared. Its watches react after each commit, and a watch on any other machine is refused here. So is a
         * definition that binds another machine as a sub-machine, since that machine is not in its registry. That
         * registry is internal and unreachable, so nothing can close it: when no {@code asyncExecutor} was given, its
         * default executor is never closed.
         */
        public StateMachine<E, S, T> build() {
            return MachineRegistry.standalone(this);
        }

        MachineKey<E, S, T> key() { return definition.key(); }

        GraphDefinition<E, S, T> definition() { return definition; }

        boolean hasIdentity() { return identity.isPresent(); }

        /**
         * Every check build(registry, bindingsAsChild) makes, without consuming the builder, so that a failed build can
         * be retried.
         */
        void validate() {
            requireUnused();
            if (getter.isEmpty() || setter.isEmpty()) {
                throw new IllegalStateException("the machine " + key() + " does not declare state(getter, setter)");
            }
            if (definition.hasAsync() && asyncTimeout.isEmpty()) {
                throw new IllegalStateException("the machine " + key()
                        + " has asynchronous transitions and does not declare asyncTimeout(...)");
            }
        }

        /** {@code bindingsAsChild} are the bindings in which the machine is the child, as the registry indexed them. */
        DefaultStateMachine<E, S, T> build(MachineRegistry registry,
                                           List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindingsAsChild) {
            validate();
            used = true;
            boolean owns = executor.isEmpty();
            Executor resolved = executor.isPresent() ? executor.get() : Executors.newVirtualThreadPerTaskExecutor();
            return new DefaultStateMachine<>(definition, getter.get(), setter.get(), identity, asyncTimeout, resolved,
                    owns, registry, bindingsAsChild);
        }

        private void requireUnused() {
            if (used) {
                throw new IllegalStateException("the builder for " + key() + " was already used");
            }
        }
    }
}
