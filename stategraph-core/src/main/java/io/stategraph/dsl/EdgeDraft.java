package io.stategraph.dsl;

import io.stategraph.api.ActionRef;
import io.stategraph.api.AsyncTransitionAction;
import io.stategraph.api.AsyncTransitionGuard;
import io.stategraph.api.EdgeKind;
import io.stategraph.api.EdgeSpec;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.GuardRef;
import io.stategraph.api.MachineKey;
import io.stategraph.api.TransitionAction;
import io.stategraph.api.TransitionGuard;
import io.stategraph.error.StateMachineException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Draft of one cell. Implements every stage: the visible return type is what restricts it. */
final class EdgeDraft<E, S extends Enum<S>>
        implements EdgeStart<E, S>, AsyncActionEdge<E, S>, MoveEdge<E, S>, StayEdge<E, S> {

    private final RowDraft<E, S> row;
    private final S from;
    private final S to;
    private Optional<EdgeKind> kind = Optional.empty();
    private String reason = "";
    private Optional<String> explicitActionName = Optional.empty();
    private Optional<TransitionAction<E, S>> syncAction = Optional.empty();
    private Optional<AsyncTransitionAction<E, S>> asyncAction = Optional.empty();
    private final List<GuardDraft<E, S>> guards = new ArrayList<>();
    private Optional<S> onFailure = Optional.empty();
    private final List<Class<? extends Throwable>> failsOn = new ArrayList<>();
    private Optional<Duration> timeout = Optional.empty();

    EdgeDraft(RowDraft<E, S> row, S from, S to) {
        this.row = row;
        this.from = from;
        this.to = to;
    }

    S from() { return from; }

    S to() { return to; }

    Optional<EdgeKind> kind() { return kind; }

    EdgeDraft<E, S> markStay() {
        kind = Optional.of(EdgeKind.STAY);
        return this;
    }

    // EdgeStart

    @Override
    public EdgeDraft<E, S> action(TransitionAction<E, S> action) {
        Objects.requireNonNull(action, "action");
        kind = Optional.of(EdgeKind.ACTION);
        syncAction = Optional.of(action);
        return this;
    }

    @Override
    public EdgeDraft<E, S> action(String name, TransitionAction<E, S> action) {
        Objects.requireNonNull(name, "name");
        explicitActionName = Optional.of(name);
        return action(action);
    }

    @Override
    public EdgeDraft<E, S> asyncAction(AsyncTransitionAction<E, S> action) {
        Objects.requireNonNull(action, "action");
        kind = Optional.of(EdgeKind.ACTION);
        asyncAction = Optional.of(action);
        return this;
    }

    @Override
    public EdgeDraft<E, S> asyncAction(String name, AsyncTransitionAction<E, S> action) {
        Objects.requireNonNull(name, "name");
        explicitActionName = Optional.of(name);
        return asyncAction(action);
    }

    @Override
    public EdgeDraft<E, S> move() {
        kind = Optional.of(EdgeKind.MOVE);
        return this;
    }

    @Override
    public RowBuilder<E, S> forbid(String reason) {
        Objects.requireNonNull(reason, "reason");
        kind = Optional.of(EdgeKind.FORBID);
        this.reason = reason;
        return row;
    }

    // Modifiers

    @Override
    public EdgeDraft<E, S> guard(TransitionGuard<E, S> guard) {
        Objects.requireNonNull(guard, "guard");
        guards.add(new GuardDraft<>(Optional.empty(), Optional.of(guard), Optional.empty()));
        return this;
    }

    @Override
    public EdgeDraft<E, S> guard(String name, TransitionGuard<E, S> guard) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(guard, "guard");
        guards.add(new GuardDraft<>(Optional.of(name), Optional.of(guard), Optional.empty()));
        return this;
    }

    @Override
    public EdgeDraft<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard) {
        Objects.requireNonNull(guard, "guard");
        guards.add(new GuardDraft<>(Optional.empty(), Optional.empty(), Optional.of(guard)));
        return this;
    }

    @Override
    public EdgeDraft<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(guard, "guard");
        guards.add(new GuardDraft<>(Optional.of(name), Optional.empty(), Optional.of(guard)));
        return this;
    }

    @Override
    public EdgeDraft<E, S> onFailure(S target) {
        Objects.requireNonNull(target, "target");
        onFailure = Optional.of(target);
        return this;
    }

    @Override
    public EdgeDraft<E, S> failsOn(Class<? extends Throwable> type) {
        Objects.requireNonNull(type, "type");
        failsOn.add(type);
        return this;
    }

    @Override
    public EdgeDraft<E, S> timeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        this.timeout = Optional.of(timeout);
        return this;
    }

    // RowBuilder: delegates to the row

    @Override
    public EdgeStart<E, S> to(S target) { return row.to(target); }

    @Override
    public StayEdge<E, S> stay() { return row.stay(); }

    @Override
    public <P, Q extends Enum<Q>> WatchBuilder<E, S, P, Q> watches(MachineKey<P, Q> peer,
                                                                   Function<? super E, ? extends Collection<? extends P>> peersOf,
                                                                   Function<? super P, Optional<E>> ownerOf) {
        return row.watches(peer, peersOf, ownerOf);
    }

    @Override
    public GraphBuilder<E, S> forbidOthers(String reason) { return row.forbidOthers(reason); }

    @Override
    public RowBuilder<E, S> from(S state) { return row.from(state); }

    @Override
    public GraphDefinition<E, S> build() { return row.build(); }

    // Assembly

    /** Turns the draft into a cell, or records the problems and returns empty. */
    Optional<EdgeSpec<E, S>> toSpec(Problems problems) {
        EdgeKind resolvedKind = kind.orElseThrow();
        String where = from + " → " + to;
        boolean valid = true;
        if (resolvedKind == EdgeKind.FORBID && reason.isBlank()) {
            problems.add("SG103", from.ordinal(), to.ordinal(), where + " is FORBID and its reason is blank");
            valid = false;
        }
        if (resolvedKind == EdgeKind.MOVE && from == to) {
            problems.add("SG104", from.ordinal(), to.ordinal(), where + " is MOVE and moves nothing: use stay()");
            valid = false;
        }
        Optional<ActionRef<E, S>> actionRef = Optional.empty();
        if (resolvedKind == EdgeKind.ACTION) {
            Object instance = syncAction.isPresent() ? syncAction.get() : asyncAction.orElseThrow();
            Optional<String> name = Names.resolve(explicitActionName, instance, "the action", where,
                    from.ordinal(), to.ordinal(), problems);
            if (name.isEmpty()) {
                valid = false;
            } else if (syncAction.isPresent()) {
                actionRef = Optional.of(new ActionRef.Sync<>(name.get(), syncAction.get()));
            } else {
                actionRef = Optional.of(new ActionRef.Async<>(name.get(), asyncAction.orElseThrow()));
            }
        }
        List<GuardRef<E, S>> guardRefs = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (GuardDraft<E, S> guard : guards) {
            if (!seen.add(guard.instance())) {
                problems.add("SG105", from.ordinal(), to.ordinal(), where + " declares the same guard twice");
                valid = false;
                continue;
            }
            Optional<String> name = Names.resolve(guard.explicitName(), guard.instance(), "a guard", where,
                    from.ordinal(), to.ordinal(), problems);
            if (name.isEmpty()) {
                valid = false;
                continue;
            }
            guardRefs.add(guard.toRef(name.get()));
        }
        for (Class<? extends Throwable> type : failsOn) {
            if (isForbiddenFailsOn(type)) {
                problems.add("SG302", from.ordinal(), to.ordinal(), where + ": failsOn does not allow " + type.getSimpleName()
                        + "; only exceptions foreign to the library, and never Throwable, Exception, RuntimeException or an Error");
                valid = false;
            }
        }
        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(new EdgeSpec<>(from, to, resolvedKind, reason, actionRef, guardRefs, onFailure, failsOn, timeout));
    }

    private static boolean isForbiddenFailsOn(Class<? extends Throwable> type) {
        return type == Throwable.class || type == Exception.class || type == RuntimeException.class
                || Error.class.isAssignableFrom(type) || StateMachineException.class.isAssignableFrom(type);
    }
}
