package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.FrozenFlow;
import java.util.Optional;
import java.util.Set;

/**
 * A read-only view of the flow of one call: the typed values that the steps and the post-commit hooks of the call
 * put, and the entity of the root call. Every root call has one flow, which all its steps share, cascaded steps and
 * the exits of sub-machines included, whatever entity each step moves, and so do its hooks; a watch reaction is a
 * call of its own, with its own flow, and so is a call that a hook makes. A guard receives this view from
 * {@link GuardContext#flow()}; an action receives the writable {@link FlowContext} from
 * {@link TransitionContext#flow()}, and a hook from {@link CommitContext#flow()}. The view a context hands out works
 * only on the thread running the guard or the action and only until the root transaction ends, as that context's
 * {@code flow()} says; the one a hook receives works only on the thread running the hook and only while it runs. Each
 * of these is equal only to itself. Once the call ended, its caller receives the flow frozen, from
 * {@link Outcome#flow()} or from the {@code flow()} of the {@code CallFailedException} the call threw: an immutable
 * copy that any thread can read for as long as it is kept, equal to every handed-out view with an equal entity and
 * equal values. Reading never creates the flow, only a write does, so a call none of whose steps or hooks wrote to
 * its flow hands out {@link #empty()}.
 */
public interface FlowView {

    /**
     * The view without values and without an entity, immutable and readable from any thread: what a call hands out
     * when none of its steps or hooks wrote to its flow, what an ignored outcome and any outcome a cascade returned
     * answer, and what a {@code CallFailedException} holds until the root of its call attaches the call's own flow. It
     * is equal to every handed-out view that holds neither values nor an entity.
     */
    static FlowView empty() {
        return FrozenFlow.EMPTY;
    }

    /**
     * The value the flow holds for {@code key}, if any: the one that the last put of an equal key stored, unless a
     * remove of an equal key came after it. It is the very object that was put, never a copy.
     *
     * @throws NullPointerException if {@code key} is null
     */
    <V> Optional<V> get(FlowKey<V> key);

    /**
     * The keys that hold a value, in the order of the write that gave each its current value: a key put again moves
     * to the end, and a removed key leaves the set. Each key is the one that put its current value, so its
     * {@link FlowKey#visible()} is that write's. The set is unmodifiable and does not follow later writes.
     */
    Set<FlowKey<?>> keys();

    /**
     * The entity of the root call: the instance its caller passed to the operation or to {@code reevaluate}, live and
     * never a copy. A cascaded step or the exit of a sub-machine reaches the root call's entity here, not its own,
     * which its context's {@code entity()} gives, and so does the hook of any hop of the call, whose own entity is
     * its first argument. A change made to it is immediate and never undone, not even when the call fails. Present in
     * the flow every step and every hook of a call receives, and in the flow a call hands out once a step or a hook
     * wrote to it; empty in {@link #empty()}, which a call that wrote nothing to its flow hands out, and in a flow
     * that belongs to no call, such as the one the five-argument constructor of {@link CommitContext} gives.
     */
    Optional<Object> entity();

    /**
     * {@link #entity()} cast to {@code type}: the same reference, without copying or converting it. Empty when
     * {@link #entity()} is.
     *
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code type} is a primitive class token; use its wrapper class instead
     * @throws ClassCastException if the entity is not an instance of {@code type}
     */
    <E> Optional<E> entity(Class<E> type);
}
