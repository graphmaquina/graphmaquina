package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.FrozenFlow;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A read-only view of the flow of one call: the typed values that the steps and the post-commit hooks of the call
 * put, the entity of the root call and, when the root machine records it, the trail of what the call took. Every root
 * call has one flow, which all its steps share, cascaded steps and the exits of sub-machines included, whatever
 * entity each step moves, and so do its hooks; a watch reaction is a call of its own, with its own flow, and so is a
 * call that a hook makes. A guard receives this view from {@link GuardContext#flow()}; an action receives the
 * writable {@link FlowContext} from {@link TransitionContext#flow()}, and a hook from {@link CommitContext#flow()}.
 * The view a context hands out works only on the thread running the guard or the action and only until the root
 * transaction ends, as that context's {@code flow()} says; the one a hook receives works only on the thread running
 * the hook and only while it runs. Each of these is equal only to itself. Once the call ended, its caller receives
 * the flow frozen, from {@link Outcome#flow()} or from the {@code flow()} of the {@code CallFailedException} the call
 * threw: an immutable copy that any thread can read for as long as it is kept, equal to every handed-out view with an
 * equal entity, equal values and an equal trail. Reading never creates the flow: a write does, and so does the start
 * of a call whose root machine records its trail. So a call that records no trail and none of whose steps or hooks
 * wrote to its flow hands out {@link #empty()}.
 */
public interface FlowView {

    /**
     * The view without values, without an entity and without a trail, immutable and readable from any thread: what a
     * call hands out when it records no trail and none of its steps or hooks wrote to its flow, what an ignored outcome
     * and any outcome a cascade returned answer, and what a {@code CallFailedException} holds until the root of its
     * call attaches the call's own flow. It is equal to every handed-out view that holds no value, no entity and no
     * trail entry.
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
     * wrote to it or when the call records its trail and runs a step; empty in {@link #empty()}, which a call that
     * records no trail and wrote nothing to its flow hands out, and so does an ignored reevaluation, and in a flow that
     * belongs to no call, such as the one the five-argument constructor of {@link CommitContext} gives.
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

    /**
     * The trail of the call: what it took, in order, one {@link FlowEntry} per hop and per sub-machine start, those of
     * cascaded steps and of the exits of sub-machines included, each at the instant it was logged; a rollback drops
     * the entries of what it undoes. Once the call ended, a rejection or a failure adds its final entry. Empty unless
     * the root machine of the call was built with {@code recordTrail}: the root machine decides for the whole call,
     * whatever the machines its steps cascade to or exit into, and a watch reaction, a call of its own, is decided by
     * its own machine. Read during the call, it lists what the call took so far. The list is unmodifiable and does not
     * follow later entries.
     */
    List<FlowEntry> trail();
}
