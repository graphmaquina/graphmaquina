package io.github.graphmaquina.test.probe;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * One invocation of a controlled asynchronous fixture, held open until the test answers it. The fixture
 * handed the engine the stage behind this handle and nothing else completes it, so the transition stays
 * parked inside the action or the guard until {@link #complete}, {@link #fail} or {@link #cancelStage} is
 * called, or until the engine's own deadline aborts it and leaves the stage pending — which is what lets a
 * test observe the machine mid-flight without sleeping.
 *
 * <p>Each invocation owns its own stage, so answering one never releases another. The three answering
 * methods return whether this very call settled the stage: a second answer, or one that arrives after the
 * fixture was closed, returns {@code false} and changes nothing. They are safe to call from any thread.
 *
 * @param <C> what the fixture recorded about the call: an {@link ActionCall} or a {@link GuardCall}
 * @param <R> what the engine is waiting for: an {@code ActionOutcome} or a {@code GuardResult}
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class PendingInvocation<C, R> {

    /** What the fixture read from the live context on entry; the context itself is deliberately not kept. */
    @Getter
    @NonNull
    private final C call;
    private final CompletableFuture<R> stage = new CompletableFuture<>();

    public boolean complete(@NonNull R response) {
        return stage.complete(response);
    }

    /** Completes the stage exceptionally with this very throwable, as the engine would see a failed call. */
    public boolean fail(@NonNull Throwable failure) {
        return stage.completeExceptionally(failure);
    }

    /**
     * Cancels the fixture's own stage, which is never a stage the test handed in from outside. Returns
     * {@code true} only to the caller that settled the stage; later cancellation attempts return {@code false}.
     */
    public boolean cancelStage() {
        return stage.completeExceptionally(new CancellationException());
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
