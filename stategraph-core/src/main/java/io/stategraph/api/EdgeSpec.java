package io.stategraph.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** A cell of the N×N matrix. The constructor validates what the runtime takes for granted. */
public record EdgeSpec<E, S extends Enum<S>>(@NonNull S from, @NonNull S to, @NonNull EdgeKind kind, @NonNull String reason,
                                             @NonNull Optional<ActionRef<E, S>> action,
                                             @NonNull List<GuardRef<E, S>> guards,
                                             @NonNull Optional<S> onFailure,
                                             @NonNull List<Class<? extends Throwable>> failsOn,
                                             @NonNull Optional<Duration> timeout) {

    public EdgeSpec {
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
