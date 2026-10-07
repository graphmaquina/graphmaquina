package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.StateMachineException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Draft of one edge, turned into a {@link TransitionSpec} when {@code endTransition()} closes it. Closing is
 * final, and a draft never closed is reported when the graph is built.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TransitionDraft<E, S extends Enum<S>, T extends Enum<T>> implements TransitionBuilder<E, S, T> {

    private final DefaultGraphBuilder<E, S, T> graph;
    private final S from;
    private final T transition;
    private final S to;
    /** The name given with the last action declared, never one inherited from an earlier declaration. */
    private Optional<String> explicitActionName = Optional.empty();
    private Optional<TransitionAction<E, S, T>> syncAction = Optional.empty();
    private Optional<AsyncTransitionAction<E, S, T>> asyncAction = Optional.empty();
    private int actions;
    private final List<GuardDraft<E, S, T>> guards = new ArrayList<>();
    private Optional<T> onFailure = Optional.empty();
    private int onFailures;
    private final List<Class<? extends Throwable>> failsOn = new ArrayList<>();
    private Optional<Duration> timeout = Optional.empty();
    private int timeouts;
    private Optional<HookRef<E, S, T>> afterCommit = Optional.empty();
    private boolean closed;

    @Override
    public TransitionDraft<E, S, T> action(@NonNull TransitionAction<E, S, T> action) {
        return declareAction(Optional.empty(), action);
    }

    @Override
    public TransitionDraft<E, S, T> action(@NonNull String name, @NonNull TransitionAction<E, S, T> action) {
        return declareAction(Optional.of(name), action);
    }

    @Override
    public TransitionDraft<E, S, T> asyncAction(@NonNull AsyncTransitionAction<E, S, T> action) {
        return declareAsyncAction(Optional.empty(), action);
    }

    @Override
    public TransitionDraft<E, S, T> asyncAction(@NonNull String name, @NonNull AsyncTransitionAction<E, S, T> action) {
        return declareAsyncAction(Optional.of(name), action);
    }

    /**
     * Every declaration is counted and the last one is stored, so a second action is reported at close and the
     * edge is refused rather than built with either one.
     */
    private TransitionDraft<E, S, T> declareAction(Optional<String> name, TransitionAction<E, S, T> action) {
        requireOpen();
        actions++;
        explicitActionName = name;
        syncAction = Optional.of(action);
        return this;
    }

    private TransitionDraft<E, S, T> declareAsyncAction(Optional<String> name, AsyncTransitionAction<E, S, T> action) {
        requireOpen();
        actions++;
        explicitActionName = name;
        asyncAction = Optional.of(action);
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> guard(@NonNull TransitionGuard<E, S, T> guard) {
        requireOpen();
        guards.add(new GuardDraft<>(Optional.empty(), Optional.of(guard), Optional.empty()));
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> guard(@NonNull String name, @NonNull TransitionGuard<E, S, T> guard) {
        requireOpen();
        guards.add(new GuardDraft<>(Optional.of(name), Optional.of(guard), Optional.empty()));
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> asyncGuard(@NonNull AsyncTransitionGuard<E, S, T> guard) {
        requireOpen();
        guards.add(new GuardDraft<>(Optional.empty(), Optional.empty(), Optional.of(guard)));
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> asyncGuard(@NonNull String name, @NonNull AsyncTransitionGuard<E, S, T> guard) {
        requireOpen();
        guards.add(new GuardDraft<>(Optional.of(name), Optional.empty(), Optional.of(guard)));
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> onFailure(@NonNull T recovery) {
        requireOpen();
        graph.requireTransition(recovery);
        onFailures++;
        onFailure = Optional.of(recovery);
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> failsOn(@NonNull Class<? extends Throwable> type) {
        requireOpen();
        failsOn.add(type);
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> timeout(@NonNull Duration duration) {
        requireOpen();
        timeouts++;
        this.timeout = Optional.of(duration);
        return this;
    }

    @Override
    public TransitionDraft<E, S, T> afterCommit(@NonNull String name, @NonNull Consumer<? super E> hook) {
        requireOpen();
        return afterCommit(HookRef.of(name, hook));
    }

    @Override
    public TransitionDraft<E, S, T> afterCommitWith(@NonNull String name,
                                                    @NonNull BiConsumer<? super E, ? super CommitContext<S, T>> hook) {
        requireOpen();
        return afterCommit(new HookRef<>(name, hook));
    }

    private TransitionDraft<E, S, T> afterCommit(HookRef<E, S, T> hook) {
        if (afterCommit.isPresent()) {
            throw new IllegalStateException(
                    where() + " already declares afterCommit(" + afterCommit.get().name() + ")");
        }
        afterCommit = Optional.of(hook);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> endTransition() {
        requireOpen();
        closed = true;
        graph.close(this);
        return graph;
    }

    private String where() {
        return from + " -" + transition + "-> " + to;
    }

    /** A closed draft has already become its edge, and a built graph reads nothing more: a late call is refused. */
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("endTransition() was already called on " + where() + " in the graph "
                    + graph.key());
        }
        graph.requireUnbuilt(where());
    }

    /** Records SG507 for a draft never closed: only {@code endTransition()} turns a draft into an edge. */
    void checkClosed(Problems problems) {
        if (!closed) {
            problems.add("SG507", from.ordinal(), transition.ordinal(),
                    where() + " was never closed with endTransition()");
        }
    }

    /** Turns the draft into an edge, or records the problems and returns empty. */
    Optional<TransitionSpec<E, S, T>> toSpec(Problems problems) {
        String where = where();
        int fromIndex = from.ordinal();
        int idIndex = transition.ordinal();
        boolean valid = true;
        Optional<ActionRef<E, S, T>> actionRef = Optional.empty();
        if (actions > 1) {
            // Names are resolved for a single action only: with more, the edge is refused whatever they are called.
            problems.add("SG507", fromIndex, idIndex, syncAction.isPresent() && asyncAction.isPresent()
                    ? where + " declares both a synchronous and an asynchronous action"
                    : where + " declares " + actions + " actions, but an edge runs at most one");
            valid = false;
        } else if (syncAction.isPresent() || asyncAction.isPresent()) {
            Object instance = syncAction.isPresent() ? syncAction.get() : asyncAction.orElseThrow();
            Optional<String> name = Names.resolve(explicitActionName, instance, "the action", where, fromIndex, idIndex,
                    problems);
            if (name.isEmpty()) {
                valid = false;
            } else if (syncAction.isPresent()) {
                actionRef = Optional.of(new ActionRef.Sync<>(name.get(), syncAction.get()));
            } else {
                actionRef = Optional.of(new ActionRef.Async<>(name.get(), asyncAction.orElseThrow()));
            }
        } else {
            if (onFailure.isPresent() || !failsOn.isEmpty()) {
                problems.add("SG507", fromIndex, idIndex, where + " declares onFailure or failsOn without an action");
                valid = false;
            }
            if (timeout.isPresent()) {
                problems.add("SG507", fromIndex, idIndex, where + " declares a timeout without an asynchronous action");
                valid = false;
            }
        }
        if (timeout.isPresent() && syncAction.isPresent()) {
            problems.add("SG507", fromIndex, idIndex, where + " declares a timeout on a synchronous action");
            valid = false;
        }
        if (onFailures > 1) {
            problems.add("SG507", fromIndex, idIndex,
                    where + " declares " + onFailures + " onFailure routes, but an edge has at most one");
            valid = false;
        }
        if (timeouts > 1) {
            problems.add("SG507", fromIndex, idIndex,
                    where + " declares " + timeouts + " timeouts, but an edge has at most one");
            valid = false;
        }
        if (timeout.isPresent() && (timeout.get().isZero() || timeout.get().isNegative())) {
            problems.add("SG511", fromIndex, idIndex, where + " declares a timeout that is not positive");
            valid = false;
        }
        List<GuardRef<E, S, T>> guardRefs = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (GuardDraft<E, S, T> guard : guards) {
            if (!seen.add(guard.instance())) {
                problems.add("SG105", fromIndex, idIndex, where + " declares the same guard twice");
                valid = false;
                continue;
            }
            Optional<String> name = Names.resolve(guard.explicitName(), guard.instance(), "a guard", where,
                    fromIndex, idIndex, problems);
            if (name.isEmpty()) {
                valid = false;
                continue;
            }
            guardRefs.add(guard.toRef(name.get()));
        }
        for (Class<? extends Throwable> type : failsOn) {
            if (isForbiddenFailsOn(type)) {
                problems.add("SG302", fromIndex, idIndex, where + ": failsOn does not allow " + type.getSimpleName()
                        + "; only throwable types foreign to the library, and never Throwable, Exception,"
                        + " RuntimeException, an Error or a CancellationException, which are never domain"
                        + " failures, nor CompletionException or ExecutionException, which are unwrapped before"
                        + " matching: declare the type of their cause");
                valid = false;
            }
        }
        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(new TransitionSpec<>(from, transition, to, actionRef, guardRefs, onFailure, failsOn, timeout,
                afterCommit));
    }

    /**
     * Types a failsOn may not list: the engine never matches an Error, a cancellation or a library exception, which
     * are never domain failures, and unwraps the exact wrapper classes before matching. The broad roots are refused
     * because they would capture technical failures too.
     */
    private static boolean isForbiddenFailsOn(Class<? extends Throwable> type) {
        return type == Throwable.class || type == Exception.class || type == RuntimeException.class
                || Error.class.isAssignableFrom(type) || StateMachineException.class.isAssignableFrom(type)
                || CancellationException.class.isAssignableFrom(type)
                || type == CompletionException.class || type == ExecutionException.class;
    }
}
