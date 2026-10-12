package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * What a wait that ends before its stage completes leaves on that stage. CompletableFuture keeps a handle dependent
 * until the stage completes, and a stage handed to many calls, as a single-flight cache hands its in-flight load to
 * every caller, would collect one per call that timed out, was interrupted or was refused its block. The engine
 * remembers the bridge of such a wait, by the stage's identity alone, so later waits for the same stage, on any
 * machine, share it: the stage carries one registration however many waits end first, every later wait still takes
 * the stage's completion exactly, and the entry goes when the stage completes or is collected. The calls through a
 * machine read their counts from the stage itself, as the engine's table is shared; the table's own tests use a table
 * of their own. No test depends on garbage collection but the one that says a dropped stage is collectable, which
 * assumes a JVM that collects on System.gc().
 */
class AwaitRetentionTest {

    private static final int CALLS = 2_000;

    /** A failure whose message must never be read; every read is counted and throws. */
    private static final class UnreadableFailure extends RuntimeException {
        private int reads;

        @Override
        public String getMessage() {
            reads++;
            throw new IllegalStateException("the message must not be read");
        }
    }

    /** A plain future's subclass that counts its handle registrations and refuses every read of its own state. */
    private static final class CountingFuture<T> extends CompletableFuture<T> {
        private volatile int registrations;

        @Override
        public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> callback) {
            registrations++;
            return super.handle(callback);
        }

        @Override
        public Future.State state() {
            throw new AssertionError("a subclass's state must not be read");
        }

        @Override
        public T resultNow() {
            throw new AssertionError("a subclass's resultNow must not be read");
        }

        @Override
        public CompletableFuture<T> toCompletableFuture() {
            throw new AssertionError("a subclass must not be converted");
        }
    }

    /**
     * A stage that exposes handle alone: every other method, hashCode, equals and toString included, which a Proxy
     * routes to its handler too, fails the test. A table that keyed it by anything but identity would call one.
     */
    private static final class OpaqueStage<T> implements InvocationHandler {
        private final CompletableFuture<T> delegate = new CompletableFuture<>();
        private int registrations;

        @SuppressWarnings("unchecked")
        private CompletionStage<T> stage() {
            return (CompletionStage<T>) Proxy.newProxyInstance(CompletionStage.class.getClassLoader(),
                    new Class<?>[] {CompletionStage.class}, this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("handle")) {
                registrations++;
                return delegate.handle((BiFunction<? super T, Throwable, ?>) arguments[0]);
            }
            throw new AssertionError("unexpected stage method " + method.getName());
        }
    }

    /**
     * DRAFT -SUBMIT-> REVIEW runs {@code action}, under {@code edgeTimeout} when there is one, and each call may wait
     * {@code asyncTimeout} in all.
     */
    private static StateMachine<Doc, DocStatus, DocTransition> acting(
            Function<TransitionContext<Doc, DocStatus, DocTransition>, CompletionStage<ActionOutcome>> action,
            Optional<Duration> edgeTimeout, Duration asyncTimeout) {
        TransitionBuilder<Doc, DocStatus, DocTransition> submitting = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).asyncAction("Submit", action::apply);
        if (edgeTimeout.isPresent()) {
            submitting = submitting.timeout(edgeTimeout.orElseThrow());
        }
        return StateMachines.from(submitting.endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build()).state(Doc::status, Doc::setStatus).asyncTimeout(asyncTimeout).build();
    }

    /** {@link #acting} where every call's action returns {@code stage}, under an edge timeout of one nanosecond. */
    private static StateMachine<Doc, DocStatus, DocTransition> acting(CompletionStage<ActionOutcome> stage) {
        return acting((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> stage,
                Optional.of(Duration.ofNanos(1)), Duration.ofSeconds(5));
    }

    /** DRAFT -SUBMIT-> REVIEW guarded by a guard that returns {@code stage}; each call may wait one nanosecond. */
    private static StateMachine<Doc, DocStatus, DocTransition> guarded(CompletionStage<GuardResult> stage) {
        return StateMachines.from(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .asyncGuard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> stage).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build()).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofNanos(1)).build();
    }

    /** Runs SUBMIT on a new draft and returns the abort it ends in, whose message must contain {@code ended}. */
    private static TransitionAbortedException aborted(StateMachine<Doc, DocStatus, DocTransition> machine,
                                                      String ended) {
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("d", DRAFT), SUBMIT));
        assertTrue(abort.getMessage().contains(ended), abort.getMessage());
        return abort;
    }

    /** Runs SUBMIT on a new draft, which must apply. */
    private static void applies(StateMachine<Doc, DocStatus, DocTransition> machine) {
        Doc doc = new Doc("d", DRAFT);
        assertInstanceOf(Outcome.Applied.class, machine.execute(doc, SUBMIT));
        assertEquals(REVIEW, doc.status());
    }

    /** Waits on {@code stage} through {@code pending} with a deadline already passed; the wait must time out. */
    private static void timesOut(Await.Pending pending, CompletionStage<?> stage) {
        assertThrows(Await.Timeout.class, () -> Await.await(pending, stage, System.nanoTime()));
    }

    /**
     * A pool of one worker that may not start a spare one: its maximum size is its parallelism, and it has no
     * saturate predicate.
     */
    private static ForkJoinPool capped() {
        return new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false, 0, 1, 1, null, 60,
                TimeUnit.SECONDS);
    }

    /** Whether {@code weak} is cleared within twenty collections. */
    private static boolean collected(WeakReference<?> weak) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
            if (weak.get() == null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void timedOutWaitsOnOneSharedStageLeaveOneRegistrationForActionsAndForGuards() {
        CompletableFuture<ActionOutcome> sharedAction = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> actions = acting(sharedAction);
        CompletableFuture<GuardResult> sharedGuard = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> guards = guarded(sharedGuard);
        for (int call = 0; call < CALLS; call++) {
            aborted(actions, "waiting for the action Submit timed out");
            aborted(guards, "waiting for the guard Ready timed out");
        }
        assertEquals(List.of(1, 1), List.of(sharedAction.getNumberOfDependents(), sharedGuard.getNumberOfDependents()),
                "registrations on each stage after " + CALLS + " timed-out calls each");

        sharedAction.complete(ActionOutcome.ok());
        sharedGuard.complete(GuardResult.pass());
        assertEquals(List.of(0, 0), List.of(sharedAction.getNumberOfDependents(), sharedGuard.getNumberOfDependents()),
                "the completion releases the registration");
        applies(actions);
        Doc doc = new Doc("guarded", DRAFT);
        assertInstanceOf(Outcome.Applied.class, guards.execute(doc, SUBMIT));
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void interruptedWaitsOnOneSharedStageLeaveOneRegistration() {
        CompletableFuture<ActionOutcome> shared = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> shared, Optional.empty(),
                Duration.ofHours(1));
        for (int call = 0; call < CALLS; call++) {
            Thread.currentThread().interrupt();
            aborted(machine, "interrupted while waiting for the action Submit");
            assertTrue(Thread.interrupted(), "the interrupted wait leaves the thread interrupted");
        }
        assertEquals(1, shared.getNumberOfDependents());
        shared.complete(ActionOutcome.ok());
        assertEquals(0, shared.getNumberOfDependents());
        applies(machine);
    }

    @Test
    void waitsRefusedByTheirPoolOnOneSharedStageLeaveOneRegistration() throws Exception {
        CompletableFuture<ActionOutcome> shared = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> shared, Optional.empty(),
                Duration.ofSeconds(5));
        try (ForkJoinPool pool = capped()) {
            for (int call = 0; call < 100; call++) {
                Throwable cause = CompletableFuture.supplyAsync(
                        () -> aborted(machine, "was refused by the thread's ForkJoinPool").getCause(), pool)
                        .get(5, TimeUnit.SECONDS);
                assertInstanceOf(RejectedExecutionException.class, cause);
            }
        }
        assertEquals(1, shared.getNumberOfDependents());
        shared.complete(ActionOutcome.ok());
        applies(machine);
    }

    /**
     * The engine reads a failure's message once, after classification, to describe the abort; the shared bridge
     * must add no read of its own, as the JDK's relays would, so the count is compared with a wait of its own.
     */
    @Test
    void aLaterWaitOnARememberedStageTakesItsThrowableExactlyAndReadsItsMessageNoMoreThanAWaitOfItsOwn() {
        UnreadableFailure control = new UnreadableFailure();
        CompletableFuture<ActionOutcome> own = new CompletableFuture<>();
        own.completeExceptionally(control);
        assertSame(control, assertThrows(TransitionAbortedException.class,
                () -> acting(own).execute(new Doc("d", DRAFT), SUBMIT)).getCause());

        CompletableFuture<ActionOutcome> shared = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(shared);
        for (int call = 0; call < 10; call++) {
            aborted(machine, "timed out");
        }
        UnreadableFailure failure = new UnreadableFailure();
        shared.completeExceptionally(failure);
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("d", DRAFT), SUBMIT));
        assertSame(failure, abort.getCause());
        assertEquals(control.reads, failure.reads, "reads of the message through the shared bridge");
    }

    @Test
    void aWaitBlockedOnARememberedBridgeIsReleasedByTheStageWithoutRegisteringAgain() throws Exception {
        CountingFuture<ActionOutcome> shared = new CountingFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> shared, Optional.empty(),
                Duration.ofSeconds(30));
        Thread.currentThread().interrupt();
        aborted(machine, "interrupted while waiting");
        assertTrue(Thread.interrupted());
        AtomicReference<Outcome<Doc, DocStatus, DocTransition>> outcome = new AtomicReference<>();
        Thread later = new Thread(() -> outcome.set(machine.execute(new Doc("later", DRAFT), SUBMIT)));
        later.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (later.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.TIMED_WAITING, later.getState(), "the later call waits for the stage");
        assertEquals(1, shared.registrations, "the later wait blocks on the remembered bridge");
        aborted(acting(shared), "timed out");
        assertEquals(Thread.State.TIMED_WAITING, later.getState(), "each wait on the bridge keeps its own deadline");
        assertEquals(1, shared.registrations);
        shared.complete(ActionOutcome.ok());
        later.join(10_000);
        assertInstanceOf(Outcome.Applied.class, outcome.get());
        assertEquals(1, shared.registrations);
    }

    @Test
    void concurrentTimedOutWaitsLeaveNoMoreRegistrationsThanThreads() throws Exception {
        int threads = 8;
        CompletableFuture<ActionOutcome> shared = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(shared);
        List<Thread> callers = new ArrayList<>();
        List<Throwable> unexpected = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            Thread caller = new Thread(() -> {
                for (int call = 0; call < 300; call++) {
                    aborted(machine, "timed out");
                }
            });
            caller.setUncaughtExceptionHandler((Thread t, Throwable e) -> {
                synchronized (unexpected) {
                    unexpected.add(e);
                }
            });
            callers.add(caller);
            caller.start();
        }
        for (Thread caller : callers) {
            caller.join(10_000);
        }
        assertEquals(List.of(), unexpected);
        int dependents = shared.getNumberOfDependents();
        assertTrue(dependents >= 1 && dependents <= threads, "registrations after " + threads * 300
                + " concurrent timed-out calls: " + dependents);
        shared.complete(ActionOutcome.ok());
        assertEquals(0, shared.getNumberOfDependents());
        applies(machine);
    }

    @Test
    void aSubclassStageIsRegisteredOnceHoweverManyWaitsEndFirst() {
        CountingFuture<ActionOutcome> shared = new CountingFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(shared);
        for (int call = 0; call < CALLS; call++) {
            aborted(machine, "timed out");
        }
        assertEquals(1, shared.registrations);
        shared.complete(ActionOutcome.ok());
        applies(machine);
        assertEquals(2, shared.registrations, "a complete stage is read through one registration of its own");
    }

    @Test
    void anOpaqueStageIsRememberedWithoutAnyMethodOfItsOwnBeingCalled() {
        OpaqueStage<ActionOutcome> opaque = new OpaqueStage<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = acting(opaque.stage());
        for (int call = 0; call < CALLS; call++) {
            aborted(machine, "timed out");
        }
        assertEquals(1, opaque.registrations);
        assertEquals(1, opaque.delegate.getNumberOfDependents());
        opaque.delegate.complete(ActionOutcome.ok());
        applies(machine);
        assertEquals(2, opaque.registrations);
    }

    @Test
    void aStageHandedToTwoMachinesCarriesOneRegistration() {
        CompletableFuture<ActionOutcome> shared = new CompletableFuture<>();
        StateMachine<Doc, DocStatus, DocTransition> first = acting(shared);
        StateMachine<Doc, DocStatus, DocTransition> second = acting(shared);
        for (int call = 0; call < CALLS; call++) {
            aborted(first, "timed out");
            aborted(second, "timed out");
        }
        assertEquals(1, shared.getNumberOfDependents());
        shared.complete(ActionOutcome.ok());
        applies(first);
        applies(second);
    }

    @Test
    void theTableKeepsTheFirstBridgeOfAStageAndDropsItOnceTheStageCompleted() {
        Await.Pending pending = new Await.Pending();
        CompletableFuture<String> stage = new CompletableFuture<>();
        Await.Bridge<String> first = new Await.Bridge<>();
        Await.Bridge<String> second = new Await.Bridge<>();
        stage.handle(first);
        stage.handle(second);
        assertTrue(pending.find(stage).isEmpty());
        pending.remember(stage, first);
        pending.remember(stage, second);
        assertSame(first, pending.find(stage).orElseThrow());
        assertEquals(1, pending.size());

        CompletableFuture<String> done = new CompletableFuture<>();
        Await.Bridge<String> late = new Await.Bridge<>();
        done.handle(late);
        done.complete("done");
        pending.remember(done, late);
        assertTrue(pending.find(done).isEmpty(), "a bridge whose stage completed is not remembered");

        stage.complete("value");
        assertTrue(pending.find(stage).isEmpty());
        assertEquals(0, pending.size());
        assertEquals(List.of("value", "value"), List.of(first.owned.resultNow(), second.owned.resultNow()));
    }

    /**
     * The bridge's callback completes it before it reads the key that remember sets once the entry is linked, and
     * remember reads the completion after that: whichever comes first, a stage that completes while its bridge is
     * remembered leaves no entry.
     */
    @Test
    void aStageThatCompletesWhileItsBridgeIsRememberedLeavesNoEntry() throws Exception {
        Await.Pending pending = new Await.Pending();
        try (ExecutorService completer = Executors.newSingleThreadExecutor()) {
            for (int round = 0; round < 20_000; round++) {
                CompletableFuture<Integer> stage = new CompletableFuture<>();
                Await.Bridge<Integer> bridge = new Await.Bridge<>();
                stage.handle(bridge);
                CountDownLatch go = new CountDownLatch(1);
                int value = round;
                Future<Boolean> completion = completer.submit(() -> {
                    go.await();
                    return stage.complete(value);
                });
                go.countDown();
                pending.remember(stage, bridge);
                assertTrue(completion.get(5, TimeUnit.SECONDS));
                assertTrue(pending.find(stage).isEmpty(), "an entry outlived its stage's completion in round " + round);
            }
        }
        assertEquals(0, pending.size());
    }

    @Test
    void distinctStagesAreRememberedAndForgottenEachByItsOwnIdentity() {
        Await.Pending pending = new Await.Pending();
        List<CompletableFuture<Integer>> stages = new ArrayList<>();
        List<Await.Bridge<Integer>> bridges = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            CompletableFuture<Integer> stage = new CompletableFuture<>();
            Await.Bridge<Integer> bridge = new Await.Bridge<>();
            stage.handle(bridge);
            pending.remember(stage, bridge);
            stages.add(stage);
            bridges.add(bridge);
        }
        assertEquals(200, pending.size());
        for (int index = 0; index < 200; index++) {
            assertSame(bridges.get(index), pending.find(stages.get(index)).orElseThrow());
        }
        for (int index = 0; index < 200; index += 2) {
            stages.get(index).complete(index);
        }
        assertEquals(100, pending.size());
        for (int index = 1; index < 200; index += 2) {
            assertSame(bridges.get(index), pending.find(stages.get(index)).orElseThrow());
        }
    }

    @Test
    void aMinimalStageIsRememberedAndItsLaterWaitTakesItsValue() {
        Await.Pending pending = new Await.Pending();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletionStage<String> minimal = source.minimalCompletionStage();
        for (int call = 0; call < CALLS; call++) {
            timesOut(pending, minimal);
        }
        assertEquals(1, pending.size());
        source.complete("value");
        assertEquals(0, pending.size());
        assertEquals("value", Await.await(pending, minimal, Long.MAX_VALUE));
    }

    @Test
    void aDroppedPendingStageIsCollectedAndItsEntryDropped() throws Exception {
        WeakReference<Object> control = new WeakReference<>(new byte[8_000_000]);
        assumeTrue(collected(control), "this JVM does not collect an unreachable value on System.gc()");
        Await.Pending pending = new Await.Pending();
        WeakReference<Object> dropped = rememberedThenDropped(pending);
        assertTrue(collected(dropped), "the table must not keep a stage the application dropped");
        // The reference handler enqueues the table's own weak reference after the collection, on its own thread.
        for (int attempt = 0; attempt < 500 && pending.size() > 0; attempt++) {
            Thread.sleep(10);
        }
        assertEquals(0, pending.size());
    }

    /** A pending stage whose waits timed out through {@code pending}, which remembers it; the caller keeps no other. */
    private static WeakReference<Object> rememberedThenDropped(Await.Pending pending) {
        CompletableFuture<String> stage = new CompletableFuture<>();
        for (int call = 0; call < 5; call++) {
            timesOut(pending, stage);
        }
        assertEquals(1, pending.size());
        assertEquals(1, stage.getNumberOfDependents());
        return new WeakReference<>(stage);
    }
}
