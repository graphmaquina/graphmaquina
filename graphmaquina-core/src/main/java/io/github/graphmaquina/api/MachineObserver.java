package io.github.graphmaquina.api;

import java.time.Duration;

/**
 * Observes every call of a registry: each {@code execute} and {@code reevaluate}, watch reactions included, but not a
 * cascade, which is part of the call that runs it. A call notifies {@link #onStarted} once, then exactly one of
 * {@link #onCompleted} and {@link #onFailed}, on the thread that runs it and before {@code execute} or
 * {@code reevaluate} returns or throws. A request refused before it starts notifies nothing: a null argument, an
 * identifier of another catalogue, or a call made where no call may start. Calls nest like a stack on their thread: a
 * watch reaction starts and ends inside the {@link CallPhase#REACTIONS} phase of the call that triggered it. A machine
 * built standalone has no observers.
 *
 * <p>An observer watches without taking part:
 * <ul>
 *   <li>A throwable that is not an Error, a checked exception included, changes neither the call nor what the other
 *   observers are told. It is logged as a warning through {@code System.getLogger("io.github.graphmaquina")}, naming
 *   the observer, the callback and the call, never the entity. Anything but an Error that the warning's backend
 *   throws, a checked exception included, is contained too; an Error from it propagates unchanged.</li>
 *   <li>An observer whose {@code onStarted} threw still receives its end event.</li>
 *   <li>An Error it throws, or that the backend of its warning throws, propagates unchanged, as everywhere in the
 *   library, and voids these guarantees for that call.</li>
 *   <li>Its callbacks run with the thread marked as running a call, so an {@code execute} or {@code reevaluate} it
 *   makes on its own thread throws {@code ReentrantTransitionException}, contained like any other exception; one
 *   submitted through {@code async()} runs as a separate call on the executor's thread, unless that executor runs
 *   tasks inline, in which case the returned future fails with that exception as its cause. To act on a commit, use
 *   a hook or a watch.</li>
 * </ul>
 * Observers are called on the thread of every call, concurrently when calls are, so they must be thread-safe and
 * quick.
 */
public interface MachineObserver {

    /** The call is about to run; nothing of it has run yet. */
    default void onStarted(MachineCall call) { }

    /**
     * The call returned {@code outcome}, the very instance its caller receives, whatever its status, with the call's
     * frozen {@link Outcome#flow()}.
     *
     * @param elapsed from just before {@link #onStarted} was notified until just before this event
     */
    default void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) { }

    /**
     * The call threw {@code failure.error()}, the very throwable its caller receives: a {@code CallFailedException} of
     * this call, whose phase and states written {@code failure} repeats and whose {@code flow()} already holds the
     * call's frozen flow, or an Error, with the phase the call was in and how far it got in writing states.
     *
     * @param elapsed from just before {@link #onStarted} was notified until just before this event
     */
    default void onFailed(MachineCall call, CallFailure failure, Duration elapsed) { }
}
