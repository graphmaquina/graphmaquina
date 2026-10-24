package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Builds machines from a {@link GraphDefinition}. */
public final class StateMachines {

    private StateMachines() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> Builder<E, S, T> from(
            @NonNull GraphDefinition<E, S, T> definition) {
        return new Builder<>(definition);
    }

    /**
     * Single-use and not thread-safe. Once a build that uses it succeeds, standalone or within a registry, every
     * method throws IllegalStateException instead of accepting configuration nothing would read. A build that fails
     * consumes nothing: fix what it reports and build again.
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Builder<E, S extends Enum<S>, T extends Enum<T>> {

        @Getter(AccessLevel.PACKAGE)
        private final GraphDefinition<E, S, T> definition;
        private Optional<Function<? super E, ? extends S>> getter = Optional.empty();
        private Optional<BiConsumer<? super E, ? super S>> setter = Optional.empty();
        private Optional<Function<? super E, ?>> identity = Optional.empty();
        private Optional<Duration> asyncTimeout = Optional.empty();
        private Optional<Executor> executor = Optional.empty();
        @Getter(AccessLevel.PACKAGE)
        private Optional<Class<? extends Machine<E, S, T>>> facade = Optional.empty();
        private Optional<InstantSource> trail = Optional.empty();
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
         * executing. It bounds the waits of the root's asynchronous guards and actions, those of every peer the root
         * cascades to, and those of every exit of a sub-machine the root runs: inside a cascade or an exit, the peer
         * or the parent waits under this root's budget, never under its own asyncTimeout. An edge's own timeout can
         * only shorten it. Any positive duration is accepted, and one of {@code Duration.ofNanos(Long.MAX_VALUE)} or
         * more means no limit. Mandatory for a machine with asynchronous transitions, and, within a registry, for a
         * machine whose calls can run an asynchronous edge implicitly: the exit of a binding in which it is the child,
         * a failure route of an implicit edge, an exit that an implicit edge leads to, or an exit selected by a
         * terminal initial descendant of a binding its own edge enters, as {@link MachineRegistry} lists them. Any
         * other machine may leave it out, and then its roots have no deadline: the asynchronous waits
         * of the peers they cascade to, and of the exits those peers' steps run, are unbounded, whatever the own
         * asyncTimeout of those peers and parents, and only an edge that declares a timeout of its own still bounds
         * its action's wait.
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

        /**
         * The executor that runs the calls submitted through {@code async(...)}. Given ForkJoinPool.commonPool() while
         * the common pool's parallelism is below two when the machine is built, the machine runs each call on a new
         * thread instead, as CompletableFuture.supplyAsync does with that pool on JDK 21: with parallelism 0 the pool
         * starts no worker, and a call queued there would never run. On JDK 25, CompletableFuture raises the common
         * pool's parallelism from 0 to 2 when its class is initialized, so a machine built after that runs its calls
         * on the pool.
         */
        public Builder<E, S, T> asyncExecutor(Executor executor) {
            requireUnused();
            Objects.requireNonNull(executor, "executor");
            this.executor = Optional.of(executor);
            return this;
        }

        /**
         * Records the trail of every root call this machine starts on the system clock,
         * {@link InstantSource#system()}, as {@link #recordTrail(InstantSource)} says.
         */
        public Builder<E, S, T> recordTrail() {
            requireUnused();
            this.trail = Optional.of(InstantSource.system());
            return this;
        }

        /**
         * Records the trail of every root call this machine starts, on {@code clock}: the flow the call hands out, in
         * its outcome or in its failure, lists in {@code trail()} each hop and each sub-machine start the call took,
         * each at the instant {@code clock} read right after the call took it, then the rejection or the failure that
         * ended the call, at the instant it ended. The root machine decides for the whole call, as it does with
         * asyncTimeout: the hops of the peers it cascades to and of the exits its steps run are recorded whatever those
         * machines declare, and a call that a machine without a trail starts records nothing, even when it cascades
         * into one with a trail. A watch reaction is a root call of its own, decided by its own machine. A call that
         * records its trail creates its flow when its step starts, so it hands out its flow, with the root entity and
         * the trail, even when none of its steps or hooks wrote to it; a reevaluation that selects no edge runs no
         * step, and its ignored outcome hands out the empty flow. The clock is read on the thread running the call,
         * once per entry the call appends, an entry a rollback later drops included, so a clock that advances on each
         * read leaves a gap for each dropped entry; a test can pass a clock it controls, such as a
         * {@link java.time.Clock}. Anything but an Error that the clock throws, a checked exception included, fails the
         * call as what an identity function throws does, an InterruptedException interrupting the thread again: the
         * entry it could not mark is left out of the trail. When it throws while the call reads the instant of the
         * Failed entry that ends a failed call, whatever failed the call, the call's {@code CallFailedException}
         * carries what it threw as suppressed, unless it threw that very exception, and its trail ends without that
         * entry; an InterruptedException suppressed there interrupts the thread again, and an Error propagates. A clock
         * that answers null counts as one that throws a NullPointerException. A machine that does not call this reads
         * no clock and keeps no mark.
         */
        public Builder<E, S, T> recordTrail(InstantSource clock) {
            requireUnused();
            Objects.requireNonNull(clock, "clock");
            this.trail = Optional.of(clock);
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

        boolean hasIdentity() { return identity.isPresent(); }

        boolean hasAsyncTimeout() { return asyncTimeout.isPresent(); }

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
            Executor resolved = executor.isPresent() ? Submit.screen(executor.get())
                    : Executors.newVirtualThreadPerTaskExecutor();
            return new DefaultStateMachine<>(definition, getter.get(), setter.get(), identity, asyncTimeout, resolved,
                    owns, registry, bindingsAsChild, trail);
        }

        private void requireUnused() {
            if (used) {
                throw new IllegalStateException("the builder for " + key() + " was already used");
            }
        }
    }
}
