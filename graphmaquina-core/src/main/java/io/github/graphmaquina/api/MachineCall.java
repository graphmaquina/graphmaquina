package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.Optional;

/**
 * One call on a machine: an {@code execute} or a {@code reevaluate}, made by the application or by a watch reaction.
 * A cascade is part of the call that runs it, not a call of its own. Each call is an instance of its own and equality
 * is identity, so two calls that ask the same thing of the same entity stay apart in a map or a set.
 * {@link #toString()} names the machine and the request, never the entity.
 */
public final class MachineCall {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;
    private final Optional<Enum<?>> transition;
    private final boolean reaction;

    /**
     * Describes one call. It refuses a null argument, an identifier from another catalogue than {@code key}'s, and a
     * reaction that requests an identifier.
     *
     * @param key the machine the call runs on
     * @param entity the entity it was called with
     * @param transition the identifier {@code execute} was asked for; empty for a {@code reevaluate}
     * @param reaction whether a watch reaction made the call, which is then a reevaluation
     * @throws IllegalArgumentException if {@code transition} belongs to another catalogue than {@code key}'s, or if a
     *     reaction requests an identifier
     */
    public MachineCall(MachineKey<?, ?, ?> key, Object entity, Optional<Enum<?>> transition, boolean reaction) {
        this.key = Objects.requireNonNull(key, "key");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.transition = Objects.requireNonNull(transition, "transition");
        if (transition.isPresent() && !key.transitions().isInstance(transition.get())) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": "
                    + transition.get());
        }
        if (reaction && transition.isPresent()) {
            throw new IllegalArgumentException("a watch reaction is a reevaluation and requests no identifier");
        }
        this.reaction = reaction;
    }

    public MachineKey<?, ?, ?> key() {
        return key;
    }

    public Object entity() {
        return entity;
    }

    /** The identifier {@code execute} was asked for; empty for a {@code reevaluate}, whatever edge its watch selects. */
    public Optional<Enum<?>> transition() {
        return transition;
    }

    /** Whether a watch reaction made this call rather than the application. */
    public boolean reaction() {
        return reaction;
    }

    /** {@code Doc/DocStatus SUBMIT}, {@code Doc/DocStatus reevaluate} or {@code Doc/DocStatus reevaluate (watch reaction)}. */
    @Override
    public String toString() {
        return key + " " + transition.map(Enum::name).orElse("reevaluate") + (reaction ? " (watch reaction)" : "");
    }
}
