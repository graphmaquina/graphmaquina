package io.stategraph.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A cell of the N×N matrix. The constructor validates what the runtime takes for granted. */
public record EdgeSpec<E, S extends Enum<S>>(S from, S to, EdgeKind kind, String reason,
                                             Optional<ActionRef<E, S>> action,
                                             List<GuardRef<E, S>> guards,
                                             Optional<S> onFailure,
                                             List<Class<? extends Throwable>> failsOn,
                                             Optional<Duration> timeout) {

    public EdgeSpec {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(guards, "guards");
        Objects.requireNonNull(onFailure, "onFailure");
        Objects.requireNonNull(failsOn, "failsOn");
        Objects.requireNonNull(timeout, "timeout");
        guards = List.copyOf(guards);
        failsOn = List.copyOf(failsOn);
        String cell = from + " → " + to;
        if ((kind == EdgeKind.ACTION) != action.isPresent()) {
            throw new IllegalArgumentException(cell + ": only ACTION carries an action, and it always carries one");
        }
        if (kind == EdgeKind.FORBID && reason.isBlank()) {
            throw new IllegalArgumentException(cell + ": FORBID requires a reason");
        }
        if (kind == EdgeKind.FORBID && !guards.isEmpty()) {
            throw new IllegalArgumentException(cell + ": FORBID does not allow guards");
        }
        if (kind != EdgeKind.ACTION && (onFailure.isPresent() || !failsOn.isEmpty())) {
            throw new IllegalArgumentException(cell + ": onFailure and failsOn are only allowed on ACTION");
        }
        if (kind == EdgeKind.MOVE && from == to) {
            throw new IllegalArgumentException(cell + ": MOVE cannot be a self-loop; use STAY");
        }
        if (timeout.isPresent() && !(action.isPresent() && action.get().isAsync())) {
            throw new IllegalArgumentException(cell + ": timeout is only allowed on an asynchronous action");
        }
        if (timeout.isPresent() && (timeout.get().isZero() || timeout.get().isNegative())) {
            throw new IllegalArgumentException(cell + ": timeout must be positive");
        }
    }

    /** Only ACTION and MOVE move the entity; they are the only edges of the graph. */
    public boolean moves() {
        return kind == EdgeKind.ACTION || kind == EdgeKind.MOVE;
    }

    /** The action is asynchronous, or some guard is. */
    public boolean isAsync() {
        if (action.isPresent() && action.get().isAsync()) {
            return true;
        }
        for (GuardRef<E, S> guard : guards) {
            if (guard.isAsync()) {
                return true;
            }
        }
        return false;
    }
}
