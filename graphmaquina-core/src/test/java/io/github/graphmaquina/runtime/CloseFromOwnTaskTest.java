package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Closing a registry from a task of one of its own default executors would wait forever, since closing an executor
 * waits for every task it accepted, the calling one included: close() refuses it with IllegalStateException before it
 * closes anything, whatever runs on that thread calls it, the call's action, a hook, a call that a hook starts, the
 * supplier of supplyAsync or a dependent of the future the task completes, and the registry stays open and usable. A
 * thread that runs no such task, the caller's thread or a task of another registry's executor, closes it as before.
 */
class CloseFromOwnTaskTest {

    private static final String REFUSAL = "close() was called from a task of the default executor of Doc/DocStatus,"
            + " which it would wait for forever: close the registry from another thread";

    /**
     * SUBMIT's action closes {@code registry} and records what that threw, if anything; DISCARD's hook runs SUBMIT
     * on a second draft, synchronously, on the thread running the hook.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> closingDocs(AtomicReference<MachineRegistry> registry,
                                                                             List<Throwable> refusals) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("CloseRegistry", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        try {
                            registry.get().close();
                        } catch (RuntimeException e) {
                            refusals.add(e);
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED)
                    .afterCommit("SubmitAnother", (Doc doc) -> registry.get().of(DOCS).execute(new Doc("another",
                            DRAFT), SUBMIT))
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)).build();
    }

    /** Asserts that the default executor of {@code registry} still accepts and runs a task. */
    private static void assertOpen(MachineRegistry registry)
            throws InterruptedException, ExecutionException, TimeoutException {
        assertEquals("runs", registry.of(DOCS).supplyAsync(() -> "runs").get(5, TimeUnit.SECONDS));
    }

    @Test
    void aCloseFromTheActionOfAnAsynchronousCallIsRefusedAndTheRegistryStaysOpen() throws Exception {
        AtomicReference<MachineRegistry> holder = new AtomicReference<>();
        List<Throwable> refusals = new CopyOnWriteArrayList<>();
        MachineRegistry registry = registry(closingDocs(holder, refusals));
        holder.set(registry);
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Applied.class,
                registry.of(DOCS).async().execute(doc, SUBMIT).get(5, TimeUnit.SECONDS));
        assertEquals(1, refusals.size());
        assertEquals(REFUSAL, assertInstanceOf(IllegalStateException.class, refusals.get(0)).getMessage());
        assertEquals(REVIEW, doc.status());
        assertOpen(registry);
        registry.close();
    }

    @Test
    void aCloseFromACallThatTheHookOfAnAsynchronousCallStartsIsRefused() throws Exception {
        AtomicReference<MachineRegistry> holder = new AtomicReference<>();
        List<Throwable> refusals = new CopyOnWriteArrayList<>();
        MachineRegistry registry = registry(closingDocs(holder, refusals));
        holder.set(registry);
        assertInstanceOf(Outcome.Applied.class,
                registry.of(DOCS).async().execute(new Doc("1", DRAFT), DISCARD).get(5, TimeUnit.SECONDS));
        assertEquals(1, refusals.size());
        assertEquals(REFUSAL, assertInstanceOf(IllegalStateException.class, refusals.get(0)).getMessage());
        assertOpen(registry);
        registry.close();
    }

    @Test
    void aCloseFromTheSupplierOfSupplyAsyncIsRefused() throws Exception {
        MachineRegistry registry = registry(closingDocs(new AtomicReference<>(), new CopyOnWriteArrayList<>()));
        CompletableFuture<String> closing = registry.of(DOCS).supplyAsync(() -> {
            registry.close();
            return "closed";
        });
        ExecutionException error = assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS));
        assertEquals(REFUSAL, assertInstanceOf(IllegalStateException.class, error.getCause()).getMessage());
        assertOpen(registry);
        registry.close();
    }

    /** The action waits until the dependent is registered, so the future completes, and runs it, on the executor. */
    @Test
    void aCloseFromADependentThatTheTaskRunsOnCompletingItsFutureIsRefused() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Wait", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        try {
                            assertTrue(registered.await(5, TimeUnit.SECONDS), "the dependent was not registered");
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        MachineRegistry registry = registry(graph);
        CompletableFuture<String> closing = registry.of(DOCS).async().execute(new Doc("1", DRAFT), SUBMIT)
                .thenApply((Outcome<Doc, DocStatus, DocTransition> outcome) -> {
                    registry.close();
                    return "closed";
                });
        registered.countDown();
        ExecutionException error = assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS));
        assertEquals(REFUSAL, assertInstanceOf(IllegalStateException.class, error.getCause()).getMessage());
        assertOpen(registry);
        registry.close();
    }

    /**
     * The caller's thread closes the registry from inside a synchronous call, and a task of one registry's default
     * executor closes another registry: neither waits for itself, so neither is refused.
     */
    @Test
    void aThreadThatRunsNoTaskOfTheRegistrysOwnExecutorsClosesIt() throws Exception {
        AtomicReference<MachineRegistry> holder = new AtomicReference<>();
        List<Throwable> refusals = new CopyOnWriteArrayList<>();
        MachineRegistry closedByItsOwnCall = registry(closingDocs(holder, refusals));
        holder.set(closedByItsOwnCall);
        assertInstanceOf(Outcome.Applied.class, closedByItsOwnCall.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
        ExecutionException rejected = assertThrows(ExecutionException.class,
                () -> closedByItsOwnCall.of(DOCS).supplyAsync(() -> "runs").get(5, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, rejected.getCause());

        AtomicReference<MachineRegistry> other = new AtomicReference<>();
        MachineRegistry closedByAnother = registry(closingDocs(other, refusals));
        other.set(closedByAnother);
        MachineRegistry closing = registry(closingDocs(other, refusals));
        assertInstanceOf(Outcome.Applied.class,
                closing.of(DOCS).async().execute(new Doc("2", DRAFT), SUBMIT).get(5, TimeUnit.SECONDS));
        ExecutionException closed = assertThrows(ExecutionException.class,
                () -> closedByAnother.of(DOCS).supplyAsync(() -> "runs").get(5, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, closed.getCause());
        assertEquals(List.of(), refusals);
        assertOpen(closing);
        closing.close();
    }
}
