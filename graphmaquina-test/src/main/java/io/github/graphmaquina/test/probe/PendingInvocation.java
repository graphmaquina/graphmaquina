package io.github.graphmaquina.test.probe;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * One invocation of a controlled asynchronous fixture, held open until the test answers it. The fixture
 * handed the engine the stage behind this handle and nothing else completes it, so the transition stays
 * parked inside the action or the guard until {@link #complete}, {@link #fail} or {@link #cancelStage} is
 * called — which is what lets a test observe the machine mid-flight without sleeping.
 *
 * <p>Each invocation owns its own stage, so answering one never releases another. The three answering
 * methods return whether this very call settled the stage: a second answer, or one that arrives after the
 * fixture was closed, returns {@code false} and changes nothing. They are safe to call from any thread.
 *
 * @param <C> what the fixture recorded about the call: an {@link ActionCall} or a {@link GuardCall}
 * @param <R> what the engine is waiting for: an {@code ActionOutcome} or a {@code GuardResult}
 */
public final class PendingInvocation<C, R> {

    private final C call;
    private final CompletableFuture<R> stage = new CompletableFuture<>();

    PendingInvocation(C call) {
        this.call = Objects.requireNonNull(call, "call");
    }

    /** What the fixture read from the live context on entry; the context itself is deliberately not kept. */
    public C call() {
        return call;
    }

    public boolean complete(R response) {
        Objects.requireNonNull(response, "response");
        return stage.complete(response);
    }

    /** Completes the stage exceptionally with this very throwable, as the engine would see a failed call. */
    public boolean fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return stage.completeExceptionally(failure);
    }

    /** Cancels the fixture's own stage, which is never a stage the test handed in from outside. */
    public boolean cancelStage() {
        return stage.cancel(false);
    }

    public boolean isDone() {
        return stage.isDone();
    }

    public boolean isCancelled() {
        return stage.isCancelled();
    }

    CompletionStage<R> stage() {
        return stage;
    }
}
