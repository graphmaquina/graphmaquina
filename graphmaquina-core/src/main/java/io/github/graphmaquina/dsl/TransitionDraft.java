package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.internal.FailureTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * One {@code recover(...)} call as it was declared, its list already copied. Kind validity, throwable validity
     * and duplicate declarations are checked when the edge closes, so their problems are reported at its position
     * instead of thrown by {@link RecoveryRule}.
     */
    private record DeclaredRecovery<T extends Enum<T>>(Class<? extends FailureKind> kind, T transition,
                                                       List<Class<? extends Throwable>> failsOn) { }

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
    private final List<DeclaredRecovery<T>> recoveries = new ArrayList<>();
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

    /**
     * Copies the list once, refuses a null element in that copy, then the closed stage and the foreign identifier,
     * like {@code onFailure}. Everything else waits for {@code toSpec}.
     */
    @Override
    public TransitionDraft<E, S, T> recover(@NonNull Class<? extends FailureKind> kind, @NonNull T transition,
                                            @NonNull List<Class<? extends Throwable>> failsOn) {
        List<Class<? extends Throwable>> types = new ArrayList<>(failsOn);
        for (Class<? extends Throwable> type : types) {
            Objects.requireNonNull(type, "failsOn contains a null element");
        }
        requireOpen();
        graph.requireTransition(transition);
        recoveries.add(new DeclaredRecovery<>(kind, transition, List.copyOf(types)));
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
        return from.name() + " -" + transition.name() + "-> " + to.name();
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

    /**
     * Turns the draft into an edge, or records the problems and returns empty. No {@link RecoveryRule} and no
     * {@link TransitionSpec} is constructed until every check has passed, so neither constructor ever refuses it.
     */
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
            if (!recoveries.isEmpty()) {
                problems.add("SG507", fromIndex, idIndex, where + " declares recover without an action");
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
        // A rejection names the guard that rejected, so the name of each guard of the edge must be its own.
        Set<String> named = new HashSet<>();
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
            if (!named.add(name.get())) {
                problems.add("SG105", fromIndex, idIndex, where + " declares two guards named " + name.get());
                valid = false;
                continue;
            }
            guardRefs.add(guard.toRef(name.get()));
        }
        for (Class<? extends Throwable> type : failsOn) {
            if (FailureTypes.isForbiddenThrowable(type)) {
                problems.add("SG302", fromIndex, idIndex, where + ": failsOn does not allow " + type.getSimpleName()
                        + "; " + FailureTypes.FORBIDDEN_THROWABLE_RULE);
                valid = false;
            }
        }
        if (!checkRecoveries(where, fromIndex, idIndex, problems)) {
            valid = false;
        }
        if (!valid) {
            return Optional.empty();
        }
        List<RecoveryRule<T>> rules = new ArrayList<>();
        for (DeclaredRecovery<T> recovery : recoveries) {
            rules.add(new RecoveryRule<>(recovery.kind(), recovery.transition(), recovery.failsOn()));
        }
        return Optional.of(new TransitionSpec<>(from, transition, to, actionRef, guardRefs, onFailure, failsOn, timeout,
                afterCommit, rules));
    }

    /**
     * Records, at this edge's position, what makes the declared rules invalid, and answers whether it recorded
     * nothing: SG303 once for each class that is not a failure kind, SG302 for each forbidden throwable type, and
     * SG304 once for each kind two rules declare and once for each throwable type declared twice across the rules and
     * failsOn. A type that only failsOn repeats is no problem.
     */
    private boolean checkRecoveries(String where, int fromIndex, int idIndex, Problems problems) {
        if (recoveries.isEmpty()) {
            return true;
        }
        boolean valid = true;
        Set<Class<?>> kinds = new HashSet<>();
        Set<Class<?>> repeatedKinds = new HashSet<>();
        Set<Class<?>> legacy = new HashSet<>(failsOn);
        Set<Class<?>> declared = new HashSet<>();
        Set<Class<?>> repeatedTypes = new HashSet<>();
        for (DeclaredRecovery<T> recovery : recoveries) {
            Class<? extends FailureKind> kind = recovery.kind();
            if (kinds.add(kind)) {
                if (!FailureTypes.isValidKind(kind)) {
                    problems.add("SG303", fromIndex, idIndex, where + ": " + FailureTypes.invalidKindMessage(kind));
                    valid = false;
                }
            } else if (repeatedKinds.add(kind)) {
                problems.add("SG304", fromIndex, idIndex,
                        where + ": failure kind " + kind.getName() + " is declared by more than one recover");
                valid = false;
            }
            for (Class<? extends Throwable> type : recovery.failsOn()) {
                if (FailureTypes.isForbiddenThrowable(type)) {
                    problems.add("SG302", fromIndex, idIndex, where + ": recover(" + kind.getName() + ", "
                            + recovery.transition().name() + ") does not allow " + type.getName() + "; "
                            + FailureTypes.FORBIDDEN_THROWABLE_RULE);
                    valid = false;
                }
                if ((!declared.add(type) || legacy.contains(type)) && repeatedTypes.add(type)) {
                    problems.add("SG304", fromIndex, idIndex, where + ": " + type.getName()
                            + " is declared more than once across recover and failsOn; a throwable type selects a"
                            + " single route");
                    valid = false;
                }
            }
        }
        return valid;
    }
}
