package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Governs the state S of entity E through the transitions T it declares. Does not protect an entity against
 * concurrent transitions: that is the caller's responsibility. Two calls on one entity from different threads are
 * not serialized, and the check that runs before phase 2 writes catches only the interleavings that leave the entity
 * in another state than the call started from.
 * A call's asynchronous waits, those of the peers it cascades to and of the exits of sub-machines it runs included,
 * are bounded by the asyncTimeout of this machine, never by a peer's or a parent's own. When this machine declares no
 * asyncTimeout, the waits of its cascaded peers, and of the exits their steps run, are unbounded, whatever the own
 * asyncTimeout of those peers and parents: only an edge that declares a timeout of its own still bounds its action's
 * wait. Within a registry, a machine must declare one when an edge that its calls can run implicitly is asynchronous:
 * the exit of a binding in which it is the child, a failure route of such an edge, an exit it leads to, or an exit
 * selected by a terminal initial descendant of a binding its own edge enters.
 * The watch reactions a call triggers are not part of it: each runs as a new root, bounded by the
 * asyncTimeout of the machine that reacts, counted from the moment that root starts. A machine built standalone runs
 * in a registry of its own that is internal and unreachable, so nothing can close it, and its default executor is
 * never closed.
 * SPI: applications receive facades generated from their declarations; this type is what the generated code wraps.
 */
public interface StateMachine<E, S extends Enum<S>, T extends Enum<T>> {

    MachineKey<E, S, T> key();

    GraphDefinition<E, S, T> definition();

    /**
     * Returns the domain-level result as a value; a technical failure throws, always a {@code CallFailedException} of
     * this call or an Error. Two domain-level results throw as well: a domain failure that no recovery rule of its edge
     * takes, on an edge without onFailure, throws {@code UnhandledFailureException}, and a rejection inside a cascade
     * aborts the whole call. A call is rejected as {@link RejectionKind#DETACHED} when this machine is bound as a
     * sub-machine, a binding finds the entity's parent, and no parent holds the entity: none is in the bound state, or
     * leaving it, with a childOf that answers this entity; and as {@link RejectionKind#RESERVED_EXIT} when the
     * identifier is an exit of the sub-machine bound to the current state. When the step ends a sub-machine that a
     * parent held when the step began, the parent's exit runs in the same call unless that parent is in flight, or the
     * call moved it out of the bound state or entered it again, and the outcome is still this step's; an exit that is
     * rejected or fails without a route aborts the call. A null argument, an identifier of another catalogue and a call
     * made where no call may start are refused before the call starts and throw as themselves. Blocks on an
     * asynchronous edge.
     */
    Outcome<E, S, T> execute(E entity, T transition);

    /**
     * Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. A failure throws,
     * always a {@code CallFailedException} of this call or an Error; a null argument and a call made where no call
     * may start are refused before the call starts and throw as themselves.
     */
    Outcome<E, S, T> reevaluate(E entity);

    /** Structural inspection: the identifiers declared from {@code state}. Evaluates no guard. */
    Set<T> transitionsFrom(S state);

    /**
     * The same machine, submitting each call to its executor: a new virtual thread by default, wherever a supplied
     * executor runs it otherwise. A null argument throws on the calling thread, and a submission the executor refuses
     * returns a failed future.
     */
    AsyncStateMachine<E, S, T> async();

    /**
     * Runs {@code call} on the machine's executor; implements {@link Machine#async}. A submission the executor
     * refuses, as the default one does after close(), returns a failed future.
     */
    <R> CompletableFuture<R> supplyAsync(Supplier<R> call);

    /**
     * Same as the two-argument {@code execute}, carrying a second, operation-specific value that guards and
     * actions read back through their context's {@code input} accessors. A {@code null} input delegates to that
     * overload; this default rejects any other value instead of silently dropping it.
     *
     * @throws NullPointerException if {@code entity} or {@code transition} is null
     * @throws UnsupportedOperationException if {@code input} is not null and this machine does not support it
     */
    default Outcome<E, S, T> execute(E entity, T transition, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(transition, "transition");
        if (input == null) {
            return execute(entity, transition);
        }
        throw new UnsupportedOperationException("this StateMachine does not support operation input");
    }
}
