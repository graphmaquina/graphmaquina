package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.AmbiguousParentException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Sub-machine composition inside a call: the binding check of a child's step, the start of the child that a hop into
 * a bound node binds, and the exits a finished step calls for, each a step of its own.
 */
final class Submachines {

    private Submachines() { }

    /**
     * What the binding check found for a child's step: the binding that holds the child, or, when some binding found
     * a parent and none holds the child, the reason of the DETACHED rejection. Both are empty for a free child.
     */
    record BindingCheck(Optional<Holder> holder, Optional<String> detached) { }

    /**
     * The binding that holds a child for one step, the parent it found, registered in the unit of work, and the size
     * of the log when the binding check ran: what is logged from there on happened during the child's step.
     */
    record Holder(SubmachineBinding<?, ?, ?, ?, ?, ?> binding, EntityRef parent, int logAtCheck) { }

    /** How a binding holds a child: its parent is in the bound node, or in flight on an edge that leaves it. */
    enum Hold { IN_NODE, LEAVING }

    /**
     * The exits a finished step calls for (spec R8, R9), run once its frame is popped, so its entity is no longer in
     * flight. Only a step that ends applied, or failed with its route taken, calls for one. Eligibility uses the
     * current pending state: an action may have restarted the entity after its own hop. The original result remains
     * the call's outcome and the diagnostic origin.
     * <ul>
     *   <li>When that state holds a sub-machine in the step's own graph, the step has just entered it, and
     *       {@link #exitEntered} runs the node's exit if the child it started already stands in a terminal state.</li>
     *   <li>Otherwise, when the binding check found the binding that holds the stepping entity and the state is
     *       terminal in the entity's graph, {@link #exitParent} runs the parent's exit.</li>
     * </ul>
     * Each exit is a step of its own, which runs its own exits in turn, so a chain climbs one binding per level.
     * Returns {@code result} itself: the outcome of a call is its own step's. When {@code result} took a failure route
     * that a throwable selected, an abort that {@link #exit} builds for this step holds that throwable as suppressed,
     * as {@link Transaction#keepingOriginal} says; the aborts of an exit's own step do not.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> exits(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, int depth,
            StepResult<S, T> result, Optional<Holder> holder) {
        if (holder.isEmpty() && !machine.bindsSubmachines()) {
            return result;
        }
        S reached;
        T last;
        Optional<Throwable> original;
        if (result instanceof StepResult.Applied<S, T> applied) {
            reached = applied.to();
            last = applied.transition();
            original = Optional.empty();
        } else if (result instanceof StepResult.Failed<S, T> failed) {
            reached = failed.movedTo();
            last = failed.failureTransition();
            original = failed.cause();
        } else {
            return result;
        }
        S current = machine.key().states().cast(tx.uow().pendingState(ref).orElseThrow());
        if (machine.bindsSubmachines()) {
            Optional<SubmachineSpec<E, S, T, ?, ?, ?>> bound = machine.definition().submachine(current);
            if (bound.isPresent() && exitEntered(tx, machine, ref, entity, bound.get(), depth, machine, ref,
                    result.from(), last, reached, original)) {
                return result;
            }
        }
        if (holder.isPresent() && machine.definition().terminals().contains(current)) {
            exitParent(tx, machine, ref, result.from(), last, current, depth, holder.get().binding(),
                    holder.get().parent(), holder.get().logAtCheck(), original);
        }
        return result;
    }

    /**
     * Completes surviving recursively started bindings deepest first, after the entering action and frame. Each
     * containing node and actual child reference comes from the current pending state and its last surviving start,
     * never another lookup. A start has no transition: until a real exit runs, failures describe the original entering
     * step, which {@code origin}, {@code originRef}, {@code from}, {@code last} and {@code reached} retain. The starts
     * one hop logs for its descendants share that hop's depth; every actual exit increases depth and processes its own
     * exits normally. When a descendant's exit enters the node again, the latest start under the parent logged since
     * the selection decides, as in {@link #exitParent}: another child cancels the node's exit, and for the same child
     * the exit runs only if its state, read again, is terminal. The position of the log at the selection marks what
     * came after; starts are never compared by value. {@code original} is the throwable that selected the failure
     * route of that entering step, if one did, for {@link #exit}.
     * Returns whether any exit ran, including an exit that already completed the containing machine.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> boolean
            exitEntered(Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity,
                        SubmachineSpec<E, S, T, P, Q, U> spec, int depth, DefaultStateMachine<?, ?, ?> origin,
                        EntityRef originRef, Enum<?> from, Enum<?> last, Enum<?> reached,
                        Optional<Throwable> original) {
        UnitOfWork uow = tx.uow();
        Optional<LogEntry.Start> selected = uow.lastStartUnder(ref);
        if (selected.isEmpty() || selected.get().node() != spec.state()
                || !selected.get().ref().key().equals(spec.childKey())) {
            return false;
        }
        LogEntry.Start start = selected.get();
        int selectedAt = uow.savepoint();
        DefaultStateMachine<P, Q, U> childMachine = tx.registry().machineFor(spec.childKey());
        Q state = spec.childKey().states().cast(uow.pendingState(start.ref()).orElseThrow());
        boolean exited = false;
        if (childMachine.bindsSubmachines()) {
            Optional<SubmachineSpec<P, Q, U, ?, ?, ?>> descendant = childMachine.definition().submachine(state);
            if (descendant.isPresent()) {
                exited = exitEntered(tx, childMachine, start.ref(),
                        spec.childKey().entity().cast(start.ref().instance()), descendant.get(), depth, origin,
                        originRef, from, last, reached, original);
            }
        }
        Optional<LogEntry.Start> reentry = uow.lastStartUnderSince(ref, selectedAt);
        if (uow.inFlight(ref).isPresent() || uow.pendingState(ref).orElseThrow() != spec.state()
                || (reentry.isPresent() && reentry.get().node() == spec.state()
                        && !reentry.get().ref().equals(start.ref()))) {
            return exited;
        }
        state = spec.childKey().states().cast(uow.pendingState(start.ref()).orElseThrow());
        if (!childMachine.definition().terminals().contains(state)) {
            return exited;
        }
        exit(tx, machine, ref, spec, start.ref(), state, origin, originRef, from, last, reached, depth, original);
        return true;
    }

    /**
     * Spec R8: the step of a child along {@code last} ended in {@code terminal}, a terminal state of its graph, under
     * {@code binding}, which held it with the parent {@code parentRef}. The parent's exit runs unless the parent is in
     * flight, entering the node or leaving it, or its pending state left the node, or the latest start under the
     * parent logged at {@code logAtCheck} or later, the size of the log when the child's binding check ran, started
     * another child in this node: an action of the child's step may have moved the parent, or entered the node again
     * with a new child.
     * Otherwise the exit retains the binding's node as its source, so a parent moved externally since the binding
     * check conflicts with that source at commit before any state is written. Neither the parent's state nor its
     * childOf is read again: the binding check at the start of the child's step decided which parent holds the child,
     * and only this unit of work's own effects since then can cancel the exit. {@code original} is the throwable that
     * selected the failure route of the child's step, if one did, for {@link #exit}.
     */
    private static <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> void exitParent(
            Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> last,
            Enum<?> terminal, int depth, SubmachineBinding<A, B, C, P, Q, U> binding, EntityRef parentRef,
            int logAtCheck, Optional<Throwable> original) {
        UnitOfWork uow = tx.uow();
        SubmachineSpec<A, B, C, P, Q, U> spec = binding.spec();
        if (uow.inFlight(parentRef).isPresent()) {
            return;
        }
        Optional<Enum<?>> pending = uow.pendingState(parentRef);
        if (pending.isPresent() && pending.get() != spec.state()) {
            return;
        }
        Optional<LogEntry.Start> reentry = uow.lastStartUnderSince(parentRef, logAtCheck);
        if (reentry.isPresent() && reentry.get().node() == spec.state() && !reentry.get().ref().equals(ref)) {
            return;
        }
        DefaultStateMachine<A, B, C> parentMachine = tx.registry().machineFor(binding.parentKey());
        exit(tx, parentMachine, parentRef, spec, ref, spec.childKey().states().cast(terminal), machine, ref, from,
                last, terminal, depth, original);
    }

    /**
     * Runs on the parent {@code parentRef} the exit that {@code terminal}, the state the child {@code child} ended in,
     * selects in {@code spec}: a step at the depth after {@code depth}, the depth of the step that ended, in the same
     * unit of work, without input and with the privilege of a reserved exit. A terminal that {@code spec} maps to no
     * exit, which only a hand-written graph can hold, and an exit deeper than maxCascadeDepth abort the step that
     * ended, which {@code machine}, {@code ref}, {@code from}, {@code last} and {@code reached} describe, and hold
     * {@code original}, the throwable that selected the failure route of that step, if one did, as suppressed before
     * they poison the call; the exit it selected is logged at TRACE before the depth is checked.
     */
    private static <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> void exit(
            Transaction tx, DefaultStateMachine<A, B, C> parentMachine, EntityRef parentRef,
            SubmachineSpec<A, B, C, P, Q, U> spec, EntityRef child, Q terminal, DefaultStateMachine<?, ?, ?> machine,
            EntityRef ref, Enum<?> from, Enum<?> last, Enum<?> reached, int depth, Optional<Throwable> original) {
        Optional<C> exit = Optional.ofNullable(spec.exits().get(terminal));
        if (exit.isEmpty()) {
            throw tx.poisonIfNested(depth, Transaction.keepingOriginal(tx.unpoisonedAbort(machine, ref, from,
                    Optional.of(last), Optional.of(reached), Optional.empty(), "the terminal " + terminal.name()
                            + " of " + spec.childKey() + " has no exit in " + spec.state().name(), Optional.empty()),
                    original));
        }
        if (tx.log().trace()) {
            tx.log().trace(depth, "submachine-exit " + CallLog.show(child) + " terminal=" + terminal.name()
                    + " parent=" + CallLog.show(parentRef) + " exit=" + exit.get().name());
        }
        if (depth + 1 > tx.uow().maxDepth()) {
            throw tx.poisonIfNested(depth, Transaction.keepingOriginal(new CascadeDepthExceededException(tx.call(),
                    tx.details(machine, ref, from, Optional.of(last), Optional.of(reached), Optional.empty()),
                    tx.uow().maxDepth()), original));
        }
        Steps.step(tx, parentMachine, parentMachine.key().entity().cast(parentRef.instance()), exit.get(), depth + 1,
                Optional.empty(), Optional.of(new Steps.ExitOf(spec.state(), child)));
    }

    /**
     * The binding check of a child's step (spec R7), for a machine that some graph binds as a sub-machine, once the
     * child's state is read and before its edge is looked up. Each binding, in the order the registry indexed them,
     * asks its parentOf for the child's parent; a parent it finds joins the unit of work with its machine's identity.
     * The binding is active when, besides, the parent's childOf answers this very child and the parent is in the bound
     * node or leaving it, as {@link #holds} says. Returns the active binding with its parent, or, when some binding
     * found a parent and none is active, the reason of the DETACHED rejection, naming the node of each binding that
     * found one, in order. Both are empty for a free child, which no binding found a parent for. Two active bindings
     * abort the step with an {@link AmbiguousParentException} as cause, except one parent that holds the child both
     * in the node its pending state names and leaving another node, on an edge between two nodes that bind the same
     * child: the node it enters, whose entry has just restarted the child, holds it. A lookup that returns null or
     * throws, and a parent or child that cannot be read, abort it too, as {@link #bindingAbort} and
     * {@link #bindingReadAbort} say. Every lookup runs inside the root, under its reentrancy marker.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> BindingCheck checkBindings(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, S from, T id, int depth) {
        Optional<Holder> active = Optional.empty();
        Optional<Hold> activeHold = Optional.empty();
        List<Enum<?>> found = new ArrayList<>(machine.bindingsAsChild().size());
        for (SubmachineBinding<?, ?, ?, ?, ?, ?> binding : machine.bindingsAsChild()) {
            Optional<EntityRef> parent = findParent(tx, machine, ref, entity, from, id, depth, binding);
            if (parent.isEmpty()) {
                continue;
            }
            found.add(binding.spec().state());
            Optional<Hold> hold = holds(tx, machine, ref, from, id, depth, binding, parent.get());
            if (hold.isEmpty()) {
                continue;
            }
            if (active.isPresent()) {
                if (active.get().parent().equals(parent.get()) && activeHold.get() != hold.get()) {
                    if (hold.get() == Hold.IN_NODE) {
                        active = Optional.of(new Holder(binding, parent.get(), tx.uow().savepoint()));
                        activeHold = hold;
                    }
                    continue;
                }
                SubmachineBinding<?, ?, ?, ?, ?, ?> first = active.get().binding();
                AmbiguousParentException ambiguous = new AmbiguousParentException(machine.key(), first.parentKey(),
                        first.spec().state(), binding.parentKey(), binding.spec().state());
                throw bindingAbort(tx, machine, ref, from, id, depth, ambiguous.getMessage(), Optional.of(ambiguous));
            }
            active = Optional.of(new Holder(binding, parent.get(), tx.uow().savepoint()));
            activeHold = hold;
        }
        if (active.isPresent() || found.isEmpty()) {
            return new BindingCheck(active, Optional.empty());
        }
        StringJoiner reason = new StringJoiner(" or ", "no parent holds it in ", "");
        for (Enum<?> node : found) {
            reason.add(node.name());
        }
        return new BindingCheck(Optional.empty(), Optional.of(reason.toString()));
    }

    /**
     * The parent that {@code binding}'s parentOf answers for {@code entity}, the child, registered in the unit of
     * work with the identity of the parent's machine; empty when it answers none.
     */
    private static <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>>
            Optional<EntityRef> findParent(Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref,
                                           Object entity, Enum<?> from, Enum<?> id, int depth,
                                           SubmachineBinding<A, B, C, P, Q, U> binding) {
        Optional<A> parent;
        try {
            parent = binding.spec().parentOf().apply(binding.spec().childKey().entity().cast(entity));
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            Throwable cause = Unwrap.unwrap(e);
            throw bindingAbort(tx, machine, ref, from, id, depth,
                    "the parentOf of " + bindingName(binding) + " threw " + Texts.shown(cause), Optional.of(cause));
        }
        if (parent == null) {
            throw bindingAbort(tx, machine, ref, from, id, depth,
                    "the parentOf of " + bindingName(binding) + " returned null", Optional.empty());
        }
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        DefaultStateMachine<A, B, C> parentMachine = tx.registry().machineFor(binding.parentKey());
        try {
            return Optional.of(tx.uow().register(parentMachine.refOf(parent.get())));
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw bindingReadAbort(tx, machine, ref, from, id, depth, binding, e);
        }
    }

    /**
     * How {@code binding} holds the child {@code ref}, if it does: the childOf of the parent {@code parentRef} answers
     * the child, as {@link #answersChild} says, and the parent's state, its pending one first, is the bound node
     * ({@link Hold#IN_NODE}), or the parent is in flight on an edge that leaves the node ({@link Hold#LEAVING}).
     * Another child, or none, is no hold; a parent whose state cannot be read, a null one with an
     * {@code UninitializedStateException}, aborts the step as {@link #bindingReadAbort} says.
     */
    private static <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> Optional<Hold>
            holds(Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> id,
                  int depth, SubmachineBinding<A, B, C, P, Q, U> binding, EntityRef parentRef) {
        SubmachineSpec<A, B, C, P, Q, U> spec = binding.spec();
        A parent = binding.parentKey().entity().cast(parentRef.instance());
        Optional<P> child;
        try {
            child = spec.childOf().apply(parent);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            Throwable cause = Unwrap.unwrap(e);
            throw bindingAbort(tx, machine, ref, from, id, depth,
                    "the childOf of " + bindingName(binding) + " threw " + Texts.shown(cause), Optional.of(cause));
        }
        if (child == null) {
            throw bindingAbort(tx, machine, ref, from, id, depth,
                    "the childOf of " + bindingName(binding) + " returned null", Optional.empty());
        }
        if (child.isEmpty()) {
            return Optional.empty();
        }
        if (!answersChild(tx, machine, ref, from, id, depth, binding, child.get())) {
            return Optional.empty();
        }
        B state = parentState(tx, machine, ref, from, id, depth, binding, parentRef, parent);
        if (state == spec.state()) {
            return Optional.of(Hold.IN_NODE);
        }
        Optional<InFlight> flight = tx.uow().inFlight(parentRef);
        if (flight.isPresent() && flight.get().spec().from() == spec.state()
                && flight.get().spec().to() != spec.state()) {
            return Optional.of(Hold.LEAVING);
        }
        return Optional.empty();
    }

    /**
     * Whether {@code child}, what the childOf of {@code binding} answered, is the child {@code ref}: an entity with the
     * child's identity, which must be the child's own instance. Another instance with the child's identity aborts the
     * step with an {@link AmbiguousIdentityException} as cause, and so does an identity that cannot be read, as
     * {@link #bindingReadAbort} says.
     */
    private static <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> boolean
            answersChild(Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                         Enum<?> id, int depth, SubmachineBinding<A, B, C, P, Q, U> binding, P child) {
        try {
            EntityRef answered = tx.registry().machineFor(binding.spec().childKey()).refOf(child);
            if (!answered.equals(ref)) {
                return false;
            }
            if (answered.instance() != ref.instance()) {
                throw new AmbiguousIdentityException(ref.key(), ref.id(), ref.instance(), answered.instance());
            }
            return true;
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw bindingReadAbort(tx, machine, ref, from, id, depth, binding, e);
        }
    }

    /**
     * The state of {@code parent}, the parent {@code parentRef} of {@code binding}, its pending one first; a state
     * that cannot be read aborts the step as {@link #bindingReadAbort} says.
     */
    private static <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> B parentState(
            Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> id, int depth,
            SubmachineBinding<A, B, C, P, Q, U> binding, EntityRef parentRef, A parent) {
        try {
            return Steps.currentState(tx, tx.registry().machineFor(binding.parentKey()), parentRef, parent);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw bindingReadAbort(tx, machine, ref, from, id, depth, binding, e);
        }
    }

    /**
     * An abort of a child's step from its binding check, before its edge was looked up: in RESOLVE at the root, in
     * ACTION and poisoning when nested. A lookup's throwable that is not an Error is the cause, unwrapped from
     * CompletionException and ExecutionException. What reading the parent or the child threw aborts the step as
     * {@link #bindingReadAbort} says.
     */
    private static TransitionAbortedException bindingAbort(Transaction tx, DefaultStateMachine<?, ?, ?> machine,
                                                           EntityRef ref, Enum<?> from, Enum<?> id, int depth,
                                                           String message, Optional<Throwable> cause) {
        return tx.abort(machine, ref, from, Optional.of(id), Optional.empty(), Optional.empty(), depth, message, cause);
    }

    /**
     * An abort of a child's step for what reading the parent or the child threw while checking {@code binding}: an
     * identity function or a state getter that threw, a null state or an ambiguous identity, the cause as it is. At the
     * root it is an abort like those of {@link #bindingAbort}. Nested, it poisons as a failure that is no failure of
     * the call, as the same failure of a cascaded entity's own identity or state does, so COLLECT never collects it and
     * stops at once. The first poison is stored before its TRACE line, as in {@link Transaction#poisonAt}.
     */
    private static TransitionAbortedException bindingReadAbort(Transaction tx, DefaultStateMachine<?, ?, ?> machine,
                                                               EntityRef ref, Enum<?> from, Enum<?> id, int depth,
                                                               SubmachineBinding<?, ?, ?, ?, ?, ?> binding,
                                                               Throwable thrown) {
        Interrupts.keep(thrown);
        TransitionAbortedException abort = tx.unpoisonedAbort(machine, ref, from, Optional.of(id), Optional.empty(),
                Optional.empty(), "checking the binding " + bindingName(binding) + " threw " + Texts.shown(thrown),
                Optional.of(thrown));
        if (depth > 0) {
            boolean first = !tx.uow().isPoisoned();
            tx.uow().poisonWrapping(abort);
            if (first) {
                tx.tracePoison(depth, abort);
            }
        }
        return abort;
    }

    /**
     * How the messages of a binding check's aborts name {@code binding}, {@code <parent key>.<node>}: only an abort
     * builds it, so a check that passes concatenates nothing.
     */
    private static String bindingName(SubmachineBinding<?, ?, ?, ?, ?, ?> binding) {
        return binding.parentKey() + "." + binding.spec().state().name();
    }

    /**
     * Starts the child that {@code spec} binds to the node {@code parent} entered: registers it with its machine's
     * identity, reads where it stands, its pending state first, logs the start at the depth of the entering step, in
     * the unit of work and, for a call that logs at TRACE, in the call's log, and starts the grandchild the same way
     * when the initial state of the child is bound too. A child that cannot be found or read aborts the step that
     * entered the node, which {@code machine}, {@code ref} and {@code edge} describe, before that step's action runs;
     * nested, the abort poisons. An Error propagates.
     */
    static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> void start(
            Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, TransitionSpec<?, ?, ?> edge,
            int depth, DefaultStateMachine<E, S, T> parentMachine, EntityRef parentRef, E parent,
            SubmachineSpec<E, S, T, P, Q, U> spec) {
        DefaultStateMachine<P, Q, U> childMachine = tx.registry().machineFor(spec.childKey());
        P child = childOf(tx, machine, ref, edge, depth, parentMachine, parent, spec);
        EntityRef childRef;
        Q previous;
        try {
            childRef = tx.uow().register(childMachine.refOf(child));
            previous = Steps.currentState(tx, childMachine, childRef, child);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw entryAbort(tx, machine, ref, edge, depth, "reading the sub-machine of " + parentMachine.key() + "."
                    + spec.state().name() + " threw " + Texts.shown(e), Optional.of(e));
        }
        Q initial = childMachine.definition().initial();
        tx.uow().appendStart(new LogEntry.Start(childRef, previous, initial, parentRef, spec.state(), depth));
        if (tx.log().trace()) {
            tx.log().trace(depth, "submachine-start " + CallLog.show(childRef) + " " + previous.name() + " -> "
                    + initial.name() + " parent=" + CallLog.show(parentRef) + " node=" + spec.state().name());
        }
        if (childMachine.bindsSubmachines()) {
            Optional<SubmachineSpec<P, Q, U, ?, ?, ?>> grandchild = childMachine.definition().submachine(initial);
            if (grandchild.isPresent()) {
                start(tx, machine, ref, edge, depth, childMachine, childRef, child, grandchild.get());
            }
        }
    }

    /**
     * The child that {@code spec}'s childOf returns for {@code parent}. An empty answer, a null one, which breaks the
     * lookup's contract, and a throwable that is not an Error, unwrapped from CompletionException and
     * ExecutionException, each abort the entering step.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>, P> P childOf(
            Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, TransitionSpec<?, ?, ?> edge,
            int depth, DefaultStateMachine<E, S, T> parentMachine, E parent, SubmachineSpec<E, S, T, P, ?, ?> spec) {
        String binding = parentMachine.key() + "." + spec.state().name();
        Optional<P> child;
        try {
            child = spec.childOf().apply(parent);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            Throwable cause = Unwrap.unwrap(e);
            throw entryAbort(tx, machine, ref, edge, depth,
                    "the childOf of " + binding + " threw " + Texts.shown(cause), Optional.of(cause));
        }
        if (child == null) {
            throw entryAbort(tx, machine, ref, edge, depth, "the childOf of " + binding + " returned null",
                    Optional.empty());
        }
        if (child.isEmpty()) {
            throw entryAbort(tx, machine, ref, edge, depth,
                    parentMachine.key() + " entered " + spec.state().name() + " with no " + spec.childKey(),
                    Optional.empty());
        }
        return child.get();
    }

    /** An abort of the step that entered a bound node along {@code edge}, before its action ran. */
    private static TransitionAbortedException entryAbort(Transaction tx, DefaultStateMachine<?, ?, ?> machine,
                                                         EntityRef ref, TransitionSpec<?, ?, ?> edge, int depth,
                                                         String message, Optional<Throwable> cause) {
        return tx.abort(machine, ref, edge.from(), Optional.of(edge.transition()), Optional.of(edge.to()),
                Optional.empty(), depth, message, cause);
    }
}
