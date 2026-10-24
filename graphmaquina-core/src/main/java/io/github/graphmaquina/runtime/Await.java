package io.github.graphmaquina.runtime;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The engine's only waiting primitive. Reads an already successful plain future directly; otherwise, joins a
 * foreign stage to a future of its own through a {@link Bridge} registered with one handle call. The bridge never
 * invokes orTimeout, completeOnTimeout or cancel on anything belonging to the user. The thread blocks on a latch that
 * the stage's completion counts down, never in CompletableFuture's get or join: called on a ForkJoinPool worker, those
 * may first run other queued asynchronous tasks on the waiting thread, which is the transaction's own, so such a task
 * would run with the thread marked as running a transition and the contexts usable, and could hold the wait past its
 * deadline. The waiting thread never runs another task. The latch is awaited through ForkJoinPool.managedBlock, as
 * CompletableFuture's own waits are, so a pool whose worker blocks here can start a spare worker. As in
 * CompletableFuture's own blocker, the deadline and the interrupt are read again before the thread blocks: the time
 * the pool takes to start a spare worker counts against the deadline, and a wait that an interrupt or a spent budget
 * has already ended never asks the pool for one. A pool that refuses to let its worker block, as a ForkJoinPool does
 * with RejectedExecutionException when it cannot replace the blocked worker, ends the wait with Rejected, which the
 * engine reports as an abort, as it does Timeout and Interrupted. As with CompletableFuture's get, a stage complete
 * when the wait begins is taken as it is, even on an interrupted thread; otherwise, once the wait ends, an interrupt
 * comes first, then completion, then the deadline. A wait that an interrupt ends leaves the thread interrupted. Once
 * complete, the stage's value is returned at once, and an exceptional completion is never read through
 * CompletableFuture's get or join: get strips a CompletionException that carries a cause, whatever its class, a
 * subclass included, and join wraps in one any throwable but a CompletionException or a CancellationException. The
 * engine takes the throwable exactly as the stage completed with it, and unwraps it as it unwraps a throwable an action
 * throws: only the exact classes CompletionException and ExecutionException, so a subclass of either is classified as
 * itself. The bridge's handle callback returns normally after copying the value or throwable and releasing the latch;
 * unlike whenComplete, it does not ask the stage to construct an exceptional dependent result, which could read the
 * throwable's message before the engine checks for poison.
 * A deadline of Long.MAX_VALUE means no limit. Any other deadline is only ever subtracted from, so one that wrapped
 * past Long.MAX_VALUE when its budget was added still leaves the exact time remaining. Any exceptional
 * completion surfaces as an ActionFailure that carries that throwable, a library exception and an Error included: the
 * engine learns of that Error only through the stage, so it reports it as the cause of an abort, while an Error
 * thrown as itself on the calling thread propagates unchanged.
 *
 * <p>A wait that ends by timeout, interrupt or refusal leaves its bridge registered on the stage, where
 * CompletableFuture keeps a handle dependent until the stage completes: no public operation removes one, and the
 * waits that do remove theirs, get and join, or the either-style combinators that unlink from the source that did not
 * complete, run queued tasks on a ForkJoinPool worker or construct a CompletionException around the stage's throwable,
 * reading its message on the thread that completes the stage. So the engine bounds what accumulates instead of
 * detaching it: the {@link Pending} table remembers the bridge of a stage whose wait ended before the stage completed,
 * and a later wait for the same stage, on any machine, blocks on that bridge instead of registering another callback.
 * A pending stage thus carries one registration for all the waits that ended on it, plus one for each wait that
 * registered its own before the first was remembered, as concurrent waits can: a stage handed to many calls, as a
 * single-flight cache hands its in-flight load to every caller, carries one registration, not one per call. A latch
 * wait that times out or is interrupted unlinks its own queue node, so the shared bridge grows with nothing. Nothing is
 * remembered for a stage that completes within its budget, and the lookup allocates nothing while the table is empty.
 */
final class Await {

    /** At or above this budget, its nanoseconds no longer fit below Long.MAX_VALUE: the budget means no limit. */
    private static final Duration UNBOUNDED = Duration.ofNanos(Long.MAX_VALUE);

    /** The bridges of the stages whose waits ended before they completed, shared by every machine. */
    private static final Pending PENDING = new Pending();

    /** The wait exceeded its deadline; the user's stage is left running. */
    static final class Timeout extends RuntimeException {
        Timeout(TimeoutException cause) {
            super(cause);
        }
    }

    /** The thread was interrupted before or during its wait for a stage that was not complete when the wait began. */
    static final class Interrupted extends RuntimeException {
        Interrupted(InterruptedException cause) {
            super(cause);
        }
    }

    /**
     * The ForkJoinPool of the waiting worker refused to let it block, because it could not replace the blocked
     * worker; the user's stage is left running.
     */
    static final class Rejected extends RuntimeException {
        Rejected(RejectedExecutionException cause) {
            super(cause);
        }
    }

    /**
     * What one handle registration joins a stage to: a future that takes the stage's value or throwable exactly as it
     * completed, and a latch its completion releases. Any number of waits may block on one bridge, each under its own
     * deadline. The callback copies the outcome and releases the latch, then drops the bridge from the table that
     * remembers it, if one does; it reads nothing of the throwable and returns normally.
     */
    static final class Bridge<T> implements BiFunction<T, Throwable, Void> {

        final CompletableFuture<T> owned = new CompletableFuture<>();
        final CountDownLatch completed = new CountDownLatch(1);
        /** The key a table remembers this bridge under, once a wait on it ended before the stage completed. */
        volatile Optional<Pending.Key> key = Optional.empty();

        @Override
        public Void apply(T value, Throwable error) {
            if (error != null) {
                owned.completeExceptionally(error);
            } else {
                owned.complete(value);
            }
            completed.countDown();
            Optional<Pending.Key> remembered = key;
            if (remembered.isPresent()) {
                remembered.orElseThrow().forget(this);
            }
            return null;
        }
    }

    /**
     * The bridges of the stages whose waits ended before they completed, at most one per stage. A stage is held
     * weakly and keyed by its identity alone, so no method of it is ever called; its entry goes when the stage
     * completes, through the bridge's callback, or once the stage is collected, at the table's next use.
     */
    static final class Pending {

        /** A stage held weakly, equal to itself and, while the stage is reachable, to any key or probe of it. */
        static final class Key extends WeakReference<Object> {
            private final Pending owner;
            private final int hash;

            private Key(Pending owner, Object stage) {
                super(stage, owner.collected);
                this.owner = owner;
                this.hash = System.identityHashCode(stage);
            }

            /** Drops this key from its table, if the table still remembers {@code bridge} under it. */
            void forget(Bridge<?> bridge) {
                owner.bridges.remove(this, bridge);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                Object stage = get();
                return other == this || stage != null && holds(other, stage);
            }
        }

        /** A stage looked up without a reference object of its own, equal to any key of that stage. */
        @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
        private static final class Probe {
            private final Object stage;

            @Override
            public int hashCode() {
                return System.identityHashCode(stage);
            }

            @Override
            public boolean equals(Object other) {
                return other == this || holds(other, stage);
            }
        }

        private final ConcurrentHashMap<Key, Bridge<?>> bridges = new ConcurrentHashMap<>();
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();

        /** Whether {@code key}, a key or a probe, stands for {@code stage}, compared by identity. */
        private static boolean holds(Object key, Object stage) {
            return key instanceof Key weak ? weak.get() == stage : key instanceof Probe probe && probe.stage == stage;
        }

        /** The bridge remembered for {@code stage}, if a wait on it ended before it completed. */
        @SuppressWarnings("unchecked")
        <T> Optional<Bridge<T>> find(CompletionStage<T> stage) {
            expunge();
            return bridges.isEmpty() ? Optional.empty()
                    : Optional.ofNullable((Bridge<T>) bridges.get(new Probe(stage)));
        }

        /**
         * Remembers {@code bridge}, registered on {@code stage}, unless a bridge is already remembered for it. The
         * bridge learns its key only once the entry is linked, and its completion is read after that, while its
         * callback completes it before reading the key: either the callback finds the key and drops the entry, or this
         * finds the completion and does.
         */
        void remember(CompletionStage<?> stage, Bridge<?> bridge) {
            expunge();
            Key key = new Key(this, stage);
            if (bridges.putIfAbsent(key, bridge) == null) {
                bridge.key = Optional.of(key);
                if (bridge.owned.isDone()) {
                    key.forget(bridge);
                }
            }
        }

        /** How many stages are remembered, once the collected ones are dropped. */
        int size() {
            expunge();
            return bridges.size();
        }

        private void expunge() {
            for (Reference<?> cleared = collected.poll(); cleared != null; cleared = collected.poll()) {
                bridges.remove(cleared);
            }
        }
    }

    private Await() { }

    /**
     * A budget in nanoseconds that saturates instead of overflowing: {@code Duration.ofNanos(Long.MAX_VALUE)} or more
     * is Long.MAX_VALUE, which means no limit. It never throws for a positive budget, the only kind its callers pass,
     * so "1000 years" or {@code ChronoUnit.FOREVER} work as the "forever" they are written to mean.
     */
    static long budgetNanos(Duration budget) {
        return budget.compareTo(UNBOUNDED) >= 0 ? Long.MAX_VALUE : budget.toNanos();
    }

    /**
     * Reads an already successful plain future without constructing a waiting bridge; every other stage goes through
     * the bridge and the managed wait. Empty means a null callback result, which the engine refuses. A concurrent
     * exceptional obtrusion between the two reads falls back to the bridge to preserve its original throwable.
     */
    static <T> Optional<T> completedOrAwait(CompletionStage<T> stage, long deadlineNanos) {
        if (stage.getClass() == CompletableFuture.class) {
            CompletableFuture<T> future = stage.toCompletableFuture();
            if (future.state() == Future.State.SUCCESS) {
                try {
                    return Optional.ofNullable(future.resultNow());
                } catch (IllegalStateException raced) {
                    // The successful observation was replaced; the bridge reads the current completion.
                }
            }
        }
        return Optional.ofNullable(await(PENDING, stage, deadlineNanos));
    }

    /**
     * Blocks on the bridge {@code pending} remembers for {@code stage}, or on a new one registered with a single handle
     * call, which {@code pending} remembers if the wait ends before the stage completes.
     */
    static <T> T await(Pending pending, CompletionStage<T> stage, long deadlineNanos) {
        Optional<Bridge<T>> remembered = pending.find(stage);
        Bridge<T> bridge = remembered.orElseGet(Bridge::new);
        if (remembered.isEmpty()) {
            stage.handle(bridge);
        }
        CompletableFuture<T> owned = bridge.owned;
        try {
            if (!owned.isDone()) {
                block(bridge.completed, deadlineNanos);
                if (!owned.isDone()) {
                    throw new Timeout(new TimeoutException());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Interrupted(e);
        } finally {
            if (remembered.isEmpty() && !owned.isDone()) {
                pending.remember(stage, bridge);
            }
        }
        if (owned.isCompletedExceptionally()) {
            // handle hands over the throwable exactly as the stage completed with it, where get or join would strip
            // or add a CompletionException; a stage that completed normally allocates nothing here.
            throw new ActionFailure(owned.handle((T value, Throwable error) -> error).join());
        }
        return owned.resultNow();
    }

    /** The time left before {@code deadlineNanos}, Long.MAX_VALUE when it sets no limit; zero or less once passed. */
    private static long left(long deadlineNanos) {
        return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
    }

    /**
     * Waits until {@code completed} is counted down, the deadline passes or the thread is interrupted. Unless the
     * deadline or an interrupt has already ended the wait, it is a managed block that, as CompletableFuture's own
     * blocker does, reads the deadline and the interrupt again before it blocks, and measures what is left of the
     * deadline only once the pool has compensated; a pool that refuses to compensate makes it throw Rejected. Last,
     * it checks for an interrupt before completion, and throws InterruptedException on an interrupted thread.
     */
    private static void block(CountDownLatch completed, long deadlineNanos) throws InterruptedException {
        if (left(deadlineNanos) > 0L && !Thread.currentThread().isInterrupted()) {
            try {
                ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                    @Override
                    public boolean block() throws InterruptedException {
                        completed.await(left(deadlineNanos), TimeUnit.NANOSECONDS);
                        return true;
                    }

                    @Override
                    public boolean isReleasable() {
                        return completed.getCount() == 0L || left(deadlineNanos) <= 0L
                                || Thread.currentThread().isInterrupted();
                    }
                });
            } catch (RejectedExecutionException e) {
                throw new Rejected(e);
            }
        }
        completed.await(0L, TimeUnit.NANOSECONDS);
    }
}
