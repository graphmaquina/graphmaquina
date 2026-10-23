package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Peers and cascading: the views a context reads its peers through, and the cascades an action asks for, to one peer
 * or to a collection, each element a nested step of the call that poisons it when it fails. The catch of every
 * cascade condemns the call first with a plain store into {@link Transaction}'s {@code condemned}, never a call.
 */
final class Cascades {

    private Cascades() { }

    /**
     * {@code unit} is the caller context's own check, which answers the call's unit of work: the view stays as alive
     * as the context that made it, and holds nothing of the call once that context is invalidated. A read stages
     * nothing, so it never poisons: neither does an unknown key here, nor an unknown facade in peerKeyFor.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(Transaction tx, ContextHandle caller,
                                                                            Supplier<UnitOfWork> unit,
                                                                            MachineKey<P, Q, U> key) {
        tx.checkPoison(caller);
        return new DefaultPeerView<>(unit, tx.registry().machineFor(key));
    }

    /**
     * Resolves the facade a peer read names once the poison is checked, in the order Transaction.cascadeKeyFor
     * follows. A read stages nothing, so an unknown facade throws here without poisoning.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> peerKeyFor(
            Transaction tx, ContextHandle caller, Class<? extends Machine<P, Q, U>> facade) {
        tx.checkPoison(caller);
        return tx.keyFor(facade);
    }

    /**
     * Cascade to a single peer. An identifier from another catalogue is refused before anything starts, as the context
     * refuses a null argument before calling this: such a refusal stages nothing and poisons nothing. Once the cascade
     * starts, any failure poisons the UnitOfWork: the parent cannot carry on as if nothing happened. The action
     * receives the failure of this call as it is, or else the new abort, as {@link #poisonWith} says, and an Error as
     * itself. A VirtualMachineError, a StackOverflowError or an OutOfMemoryError for one, leaves at once, unrecorded:
     * {@link Transaction#settle} records it once the action goes on.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(Transaction tx, ContextHandle caller,
                                                                              MachineKey<P, Q, U> key, P peer, U id) {
        return cascade(tx, caller, key, peer, id, Optional.empty());
    }

    /**
     * {@link #cascade(Transaction, ContextHandle, MachineKey, Object, Enum)}, or one element of a FAIL_FAST
     * cascadeAll, whose {@code policy} the element's TRACE line names; it is empty for a single cascade and when the
     * call does not log at TRACE. An element runs inside the catch of {@link Transaction#cascadeEach}, which condemns
     * the call: since the elements before it may have staged their hops, whatever strikes while its arguments are
     * checked again poisons the call.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(
            Transaction tx, ContextHandle caller, MachineKey<P, Q, U> key, P peer, U id,
            Optional<CascadePolicy> policy) {
        checkCascade(tx, caller);
        Transaction.requireTransition(key, id);
        try {
            return cascadeOne(tx, caller, key, peer, id, policy);
        } catch (Throwable error) {
            // First a store, which cannot overflow: whatever the rest of this catch throws, a cascade failed.
            tx.condemned = true;
            if (error instanceof VirtualMachineError fatal) {
                // Recording it would need the stack or the memory that ran out; settle records it.
                throw fatal;
            }
            CallFailedException poisoned = poisonWith(tx, caller, key.toString(), error);
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw poisoned;
        }
    }

    /**
     * Poisons the transaction with a failure that left a cascade, and returns the failure of this call as it is, or
     * else the new abort. A failure of this call is itself the poison. Anything else, an engine exception that
     * describes no call, a throwable from a callback the engine does not wrap such as an identity function, a library
     * exception of another call or an Error other than a VirtualMachineError, which the cascades let through before
     * they call this, poisons through an abort of the caller's step that names the cascade and carries it as cause;
     * COLLECT never collects that abort. A transaction keeps its first poison. An Error poisons
     * without a TRACE line: the cascade rethrows it as itself, and no line follows an Error that ends a call. The abort
     * prints the failure and the entities in flight through {@link Texts#shown} and {@link EntityRef#toString()}, so
     * one that cannot be printed shows a marker; when printing them throws an Error, the transaction is first poisoned
     * by an abort that names the failure's class and each entity's machine alone, and then that Error propagates,
     * unless the failure is itself an Error: that failure then propagates as itself, with the Error of printing it as
     * suppressed.
     */
    static CallFailedException poisonWith(Transaction tx, ContextHandle caller, String target, Throwable error) {
        if (error instanceof CallFailedException own && tx.owns(own)) {
            tx.poisonAt(caller.contextDepth(), own);
            return own;
        }
        return poisonThrough(tx, caller, target, error);
    }

    /**
     * {@link #poisonWith} for what it wraps, whatever {@code error} is: poisons the transaction, unless it is already
     * poisoned, through an abort of the caller's step that names the cascade to {@code target} and carries
     * {@code error}, and returns that abort.
     */
    private static TransitionAbortedException poisonThrough(Transaction tx, ContextHandle caller, String target,
                                                            Throwable error) {
        Interrupts.keep(error);
        String cascade = "the cascade to " + target + " failed: ";
        TransitionAbortedException wrapper;
        try {
            wrapper = new TransitionAbortedException(tx.call(), tx.phase(), tx.details(caller),
                    cascade + Texts.shown(error), error);
        } catch (Error fatal) {
            tx.uow().poisonWrapping(new TransitionAbortedException(tx.call(), tx.phase(), tx.bareDetails(caller),
                    cascade + error.getClass().getName(), error));
            if (error instanceof Error original) {
                if (original != fatal) {
                    original.addSuppressed(fatal);
                }
                throw original;
            }
            throw fatal;
        }
        boolean first = !tx.uow().isPoisoned();
        tx.uow().poisonWrapping(wrapper);
        if (first && !(error instanceof Error)) {
            tx.tracePoison(caller.contextDepth(), wrapper);
        }
        return wrapper;
    }

    /**
     * Order of checks: identity, re-entry, depth, and only then the step, as {@link #cascadeTo} says. {@code policy}
     * is the policy of the cascadeAll that runs this element, for its TRACE line, as {@link #cascadeTo} says.
     */
    private static <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascadeOne(
            Transaction tx, ContextHandle caller, MachineKey<P, Q, U> key, P peer, U id,
            Optional<CascadePolicy> policy) {
        DefaultStateMachine<P, Q, U> machine = tx.registry().machineFor(key);
        machine.requireTransition(id);
        return cascadeTo(tx, caller, machine, tx.uow().register(machine.refOf(peer)), peer, id, policy);
    }

    /**
     * The cascade to {@code peer}, once its identity is registered as {@code ref}: re-entry, depth, and only then the
     * step. A request that resolves no edge is rejected, and a request on an entity already in flight is a cycle. A
     * call that logs at TRACE logs the cascade before the checks and its result once the step returns, both at the
     * depth of the step that asked for it; {@code policy} is the policy of the cascadeAll that runs this element, which
     * the first line names, empty for a single cascade and when the call does not log at TRACE.
     */
    private static <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascadeTo(
            Transaction tx, ContextHandle caller, DefaultStateMachine<P, Q, U> machine, EntityRef ref, P peer, U id,
            Optional<CascadePolicy> policy) {
        int depth = caller.contextDepth() + 1;
        if (tx.log().trace()) {
            tx.log().trace(caller.contextDepth(), "cascade " + CallLog.show(ref) + " " + id.name()
                    + (policy.isPresent() ? " policy=" + policy.get().name() : ""));
        }
        Optional<InFlight> flight = tx.uow().inFlight(ref);
        if (flight.isPresent()) {
            throw cascadeCycle(tx, caller, flight.orElseThrow(), id);
        }
        if (depth > tx.uow().maxDepth()) {
            throw new CascadeDepthExceededException(tx.call(), tx.details(caller), tx.uow().maxDepth());
        }
        Outcome<P, Q, U> outcome = StepResult.outcomeOf(peer,
                Steps.transition(tx, machine, peer, id, depth, Optional.empty()));
        if (tx.log().trace()) {
            tx.log().trace(caller.contextDepth(), "cascade-result " + CallLog.show(ref) + " " + id.name() + " code="
                    + outcome.code().name());
        }
        return outcome;
    }

    /** The cascade path plus the entity that was re-entered, with the identifier in flight and the one requested. */
    private static CascadeCycleException cascadeCycle(Transaction tx, ContextHandle caller, InFlight flight,
                                                      Enum<?> requested) {
        List<String> route = new ArrayList<>(tx.uow().path());
        route.add(flight.ref().toString());
        return new CascadeCycleException(tx.call(), tx.details(caller), route, flight.transition(), requested);
    }

    /**
     * FAIL_FAST aborts at the first failure. COLLECT tries every element under its own savepoint, rolls back
     * the failed one, and at the end poisons with the aggregate. It is the only place that clears the poison.
     * COLLECT gathers the aborts of the call only: any other failure poisons, through an abort that carries it, and
     * stops the cascade at once, a nested one too, once an element's step was poisoned through such an abort, whether
     * its action let it through, caught it or replaced it. The abort of an element's step whose binding check could
     * not read a parent or a child poisons as such a failure, as Submachines.bindingReadAbort says, so it stops the
     * cascade too. A VirtualMachineError stops it at once too, as itself and unrecorded, as a single cascade lets it
     * through, and so does the abort of an element's step that recorded one its action caught.
     * Once the poison is checked, the collection is copied, and every later step reads the copy alone: the elements
     * checked are the elements run, and a change to the collection while the cascade runs, by an element's action for
     * instance, changes neither which elements run nor their order. Copying is part of accepting the arguments: an
     * exception or an Error the collection throws while it is copied propagates as it is, and stages and poisons
     * nothing. The arguments are refused before any element runs, as cascade refuses its own, and the refusal stages
     * nothing and poisons nothing: a null element first, then an identifier from another catalogue, which the key
     * carries. The machine is looked up next, before any element runs, so an unknown key poisons whatever valid
     * elements the collection holds, and an empty collection too, as it does in cascade. Once the identifier is
     * accepted, whatever ends the cascade poisons the call, as {@link Transaction#cascadeEach} says, an Error that
     * strikes between two elements, after the first one staged its hop, too.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(
            Transaction tx, ContextHandle caller, MachineKey<P, Q, U> key, Collection<? extends P> peers, U id,
            CascadePolicy policy) {
        checkCascade(tx, caller);
        List<P> elements = elementsOf(peers);
        return tx.cascadeEach(caller, key, elements, id, policy);
    }

    /**
     * cascadeAll by facade type, in the same order as by key, except that the identifier is checked after the facade
     * resolves: only a resolved facade gives the catalogue it is checked against. A null element is refused before the
     * facade is resolved, so it never poisons, and an unknown facade then poisons.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(
            Transaction tx, ContextHandle caller, Class<? extends Machine<P, Q, U>> facade,
            Collection<? extends P> peers, U id, CascadePolicy policy) {
        checkCascade(tx, caller);
        List<P> elements = elementsOf(peers);
        return tx.cascadeEach(caller, tx.cascadeKeyFor(caller, facade), elements, id, policy);
    }

    /** A copy of {@code peers}, read once, with a null element refused. */
    private static <P> List<P> elementsOf(Collection<? extends P> peers) {
        List<P> elements = new ArrayList<>(peers);
        for (P peer : elements) {
            Objects.requireNonNull(peer, "peers contains a null element");
        }
        return elements;
    }

    /**
     * One element of a COLLECT cascadeAll, which runs inside the catch of {@link Transaction#cascadeEach}: under a
     * savepoint of its own, its identity is registered and its cascade runs as {@link #cascadeTo} says, its outcome
     * added to {@code outcomes}. Its catch condemns the call first with a store, as a single cascade's does; then a
     * VirtualMachineError leaves as itself, the abort of the call's own step is collected as {@link #collectAbort}
     * says, unless the poison wraps a foreign failure, and anything else poisons the call and ends the cascade.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> void collect(
            Transaction tx, ContextHandle caller, MachineKey<P, Q, U> key, P peer, U id,
            Optional<CascadePolicy> traced, List<Outcome<P, Q, U>> outcomes, List<TransitionAbortedException> errors) {
        int savepoint = tx.uow().savepoint();
        Optional<EntityRef> target = Optional.empty();
        try {
            DefaultStateMachine<P, Q, U> machine = tx.registry().machineFor(key);
            EntityRef ref = tx.uow().register(machine.refOf(peer));
            if (tx.log().trace()) {
                target = Optional.of(ref);
            }
            outcomes.add(cascadeTo(tx, caller, machine, ref, peer, id, traced));
        } catch (Throwable error) {
            // As in cascade: a store first, then a VirtualMachineError leaves as itself.
            tx.condemned = true;
            if (error instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (error instanceof TransitionAbortedException abort && tx.owns(abort)
                    && !tx.uow().poisonWrapsAForeignFailure()) {
                collectAbort(tx, caller, key, id, savepoint, target, abort, errors);
                return;
            }
            CallFailedException poisoned = poisonWith(tx, caller, key.toString(), error);
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw poisoned;
        }
    }

    /**
     * Collects {@code abort}, the abort of the call's own step that ended a COLLECT element: poisons the call with it
     * through {@link Transaction#poisonAt}, so an element that fails before its step runs, a cycle or the depth limit,
     * logs its poison as a single cascade's does, rolls the element back to {@code savepoint}, logs the rollback at
     * TRACE with the abort once the identity of the element, {@code target}, was registered, then adds the abort to
     * {@code errors} and clears the poison and the call's condemnation. An element whose step aborted poisoned the call
     * inside the step, which logged it. A rollback that an identity's equals or hashCode stops is a failure that leaves
     * the cascade like any other, whatever it threw: the abort of the caller's step that carries it, through
     * {@link #poisonThrough}, replaces the element's abort as the poison, holds that abort as suppressed, and reaches
     * the action; a COLLECT that encloses this one collects no such poison, so it stops at once too.
     */
    private static <P, Q extends Enum<Q>, U extends Enum<U>> void collectAbort(
            Transaction tx, ContextHandle caller, MachineKey<P, Q, U> key, U id, int savepoint,
            Optional<EntityRef> target, TransitionAbortedException abort, List<TransitionAbortedException> errors) {
        tx.poisonAt(caller.contextDepth(), abort);
        try {
            tx.uow().rollbackTo(savepoint);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable stopped) {
            tx.uow().clearPoison();
            TransitionAbortedException interrupted = poisonThrough(tx, caller, key.toString(), stopped);
            interrupted.addSuppressed(abort);
            throw interrupted;
        }
        if (target.isPresent()) {
            tx.log().trace(caller.contextDepth(), "collect-rollback " + CallLog.show(target.get()) + " "
                    + id.name() + " error=" + CallLog.show(abort));
        }
        errors.add(abort);
        tx.uow().clearPoison();
        tx.condemned = false;
    }

    /**
     * The aggregate of the aborts a COLLECT gathered, {@code errors}, stored as the call's poison through
     * {@link Transaction#poisonAt} and returned for the cascade to throw. When printing the entities in flight for it
     * throws an Error, an aggregate whose path names each entity's machine alone poisons the transaction first, as
     * {@link #poisonWith} says, and then that Error propagates.
     */
    static CascadeException aggregate(Transaction tx, ContextHandle caller, List<TransitionAbortedException> errors) {
        CascadeException aggregate;
        try {
            aggregate = new CascadeException(tx.call(), tx.details(caller), errors);
        } catch (Error fatal) {
            tx.uow().poison(new CascadeException(tx.call(), tx.bareDetails(caller), errors));
            throw fatal;
        }
        tx.poisonAt(caller.contextDepth(), aggregate);
        return aggregate;
    }

    /** Looks the machine of a cascade up inside the poisoning scope: an unknown key poisons, as in cascadeOne. */
    static void requireMachine(Transaction tx, ContextHandle caller, MachineKey<?, ?, ?> key) {
        try {
            tx.registry().anyMachineFor(key);
        } catch (StateMachineException e) {
            throw poisonWith(tx, caller, key.toString(), e);
        }
    }

    /**
     * A commit callback cannot add a hop after phase 2 has fixed the changes it will write, and an identity's equals
     * or hashCode cannot add one while a rollback undoes the log, as {@link UnitOfWork#rollbackTo} says. The rollback
     * is checked before the poison: COLLECT rolls an aborted element back with the call poisoned, and that refusal is
     * the same as the one a failure route's rollback gives.
     */
    static void checkCascade(Transaction tx, ContextHandle caller) {
        if (tx.uow().rollingBack()) {
            throw new IllegalStateException("cascades cannot run while the call rolls back a step");
        }
        tx.checkPoison(caller);
        if (tx.phase() == CallPhase.COMMIT || tx.phase() == CallPhase.HOOK || tx.phase() == CallPhase.REACTIONS) {
            throw new IllegalStateException("cascades can only run before the commit");
        }
    }
}
