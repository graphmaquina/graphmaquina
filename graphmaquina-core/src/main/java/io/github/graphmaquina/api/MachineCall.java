package io.github.graphmaquina.api;

import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/**
 * One call on a machine: an {@code execute}, a {@code reevaluate} or an {@code advance}, made by the application, by
 * a watch reaction or by an automatic step. A cascade is part of the call that runs it, not a call of its own. Each
 * call is an instance of its own and equality is identity, so two calls that ask the same thing of the same entity stay
 * apart in a map or a set.
 * {@link #toString()} names the machine and the request, never the entity.
 */
@Getter
public final class MachineCall {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    /** What the call asks of the machine. */
    private final CallRequest request;

    /**
     * The identifier {@code execute} was asked for; empty for every other request, whatever edge its watch selects.
     */
    private final Optional<Enum<?>> transition;

    /** Who made the call. */
    private final CallOrigin origin;

    /**
     * Describes one call. It refuses a null argument, an identifier from another catalogue than {@code key}'s, which
     * the refusal names by {@code name()}, an execute without an identifier, an identifier on any other request, a
     * watch reaction that is not a reevaluation, and an automatic step that is not an advance.
     *
     * @param key the machine the call runs on
     * @param entity the entity it was called with
     * @param request what the call asks of the machine
     * @param transition the identifier {@code execute} was asked for; empty for every other request
     * @param origin who made the call
     * @throws IllegalArgumentException if {@code transition} belongs to another catalogue than {@code key}'s, if it is
     *     absent for an execute or present for another request, if a watch reaction requests anything but a
     *     reevaluation, or if an automatic step requests anything but an advance
     */
    public MachineCall(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull CallRequest request,
            @NonNull Optional<Enum<?>> transition, @NonNull CallOrigin origin) {
        this.key = key;
        this.entity = entity;
        this.request = request;
        this.transition = transition;
        this.origin = origin;
        boolean executes = request == CallRequest.EXECUTE;
        if (executes && transition.isEmpty()) {
            throw new IllegalArgumentException("an execute requests an identifier");
        }
        if (!executes && transition.isPresent()) {
            throw new IllegalArgumentException("only an execute requests an identifier");
        }
        if (transition.isPresent() && !key.transitions().isInstance(transition.get())) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": "
                    + transition.get().name());
        }
        if (origin == CallOrigin.WATCH_REACTION && request != CallRequest.REEVALUATE) {
            throw new IllegalArgumentException("a watch reaction is a reevaluation");
        }
        if (origin == CallOrigin.AUTOMATIC_STEP && request != CallRequest.ADVANCE) {
            throw new IllegalArgumentException("an automatic step is an advance");
        }
    }

    /**
     * {@code Doc/DocStatus SUBMIT}, {@code Doc/DocStatus reevaluate}, {@code Doc/DocStatus advance},
     * {@code Doc/DocStatus reevaluate (watch reaction)} or {@code Doc/DocStatus advance (automatic step)}.
     */
    @Override
    public String toString() {
        return key + " " + transition.map(Enum::name).orElseGet(() -> request.name().toLowerCase(Locale.ROOT))
                + (origin == CallOrigin.WATCH_REACTION ? " (watch reaction)"
                : origin == CallOrigin.AUTOMATIC_STEP ? " (automatic step)" : "");
    }
}
