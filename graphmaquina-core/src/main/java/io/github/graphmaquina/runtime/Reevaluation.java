package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.internal.CallFailureFlows;
import io.github.graphmaquina.internal.FrozenFlow;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A reevaluation of an entity of a machine: under the reentrancy marker, the state read and the watch of that state,
 * which selects the edge that then runs as a root call, or none.
 */
final class Reevaluation {

    private Reevaluation() { }

    /**
     * chain counts reaction -> transition -> reaction, so a chain above 0 is a watch reaction. Exceeding
     * maxWatchChain throws WatchChainExceededException after the reentrancy check and the state read, before any
     * watch function runs. The state read and the watch functions, peersOf and the conditions, run with the
     * reentrancy marker set, so none of them can start a transition on this thread: a nested root would begin a chain
     * of its own that maxWatchChain never counts. The marker is released before the selected edge runs as a root.
     * Observers see the whole of it as one call, which ends in RESOLVE if it throws before an edge runs, and so does
     * its log. Such a failure has no transaction. When {@code machine} records its trail, the failure carries a flow
     * of its own, attached before the observers hear of it: the entity, no value, and at most one Failed entry, read
     * then, as OutcomeFlows.endWith says. When the clock throws anything but an Error or answers null, the entry is
     * omitted; a distinct clock failure is suppressed on the call's failure. Without a trail it carries the empty
     * flow, and an Error carries none. An Error the clock throws while it reads that entry's instant propagates in
     * place of the failure, and the observers hear of
     * that Error. The log's start line names {@code trigger}: the id of the call whose commit triggered this watch
     * reaction, when that call logged, and empty for a reevaluation the application made.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Outcome<E, S, T> reevaluate(DefaultStateMachine<E, S, T> machine,
                                                                               E entity, int chain,
                                                                               Optional<Long> trigger) {
        Reentrancy.check(machine.key(), entity);
        boolean reaction = chain > 0;
        CallLog log = CallLog.open(trigger);
        Optional<Observed> observed = RootCall.observe(machine, entity, Optional.empty(), reaction, log);
        S state;
        Optional<WatchSpec<E, S, T, ?, ?, ?>> watch;
        Optional<T> selected = Optional.empty();
        try {
            if (log.debug()) {
                log.start(machine.key(), "reevaluate");
            }
            boolean[] marker = Reentrancy.enter(machine.key(), entity);
            try {
                state = stateOf(machine, entity, observed, reaction);
                requireChain(machine, entity, observed, reaction, state, chain);
                watch = machine.definition().watch(state);
                if (watch.isPresent()) {
                    selected = selection(machine, watch.get(), entity, observed, reaction, state);
                }
            } finally {
                marker[0] = false;
            }
        } catch (Throwable thrown) {
            unresolvedEnd(machine, entity, observed, log, thrown);
            throw thrown;
        }
        if (watch.isEmpty()) {
            return RootCall.ignored(observed, log, new Outcome.Ignored<>(entity, state, IgnoreReason.NO_WATCH));
        }
        if (selected.isEmpty()) {
            return RootCall.ignored(observed, log, new Outcome.Ignored<>(entity, state, IgnoreReason.NO_RULE_MATCHED));
        }
        T resolved = selected.orElseThrow();
        return RootCall.run(machine, entity, Optional.empty(), reaction, observed, log,
                (Transaction tx) -> tx.transition(machine, entity, resolved, 0), chain);
    }

    /** The state the reevaluation reads; a getter that throws or answers null aborts it as {@link #unresolved} says. */
    private static <E, S extends Enum<S>, T extends Enum<T>> S stateOf(DefaultStateMachine<E, S, T> machine,
                                                                       E entity, Optional<Observed> observed,
                                                                       boolean reaction) {
        try {
            return machine.readState(entity);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw unresolved(machine, entity, observed, reaction, Optional.empty(), "reading the entity threw ", e);
        }
    }

    /** Refuses a watch reaction past maxWatchChain once the state is read, before any watch function runs. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void requireChain(
            DefaultStateMachine<E, S, T> machine, E entity, Optional<Observed> observed, boolean reaction, S state,
            int chain) {
        if (chain > machine.registry().maxWatchChain()) {
            AbortDetails details = new AbortDetails(machine.key(), entity, Optional.of(state), Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), List.of());
            throw new WatchChainExceededException(RootCall.callOf(machine, entity, observed, reaction), details,
                    machine.registry().maxWatchChain());
        }
    }

    /**
     * The identifier that {@code watch}, the watch of {@code state}, selects, as {@link #firstMatch} says; a watch
     * function that throws aborts the reevaluation as {@link #unresolved} says.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<T> selection(
            DefaultStateMachine<E, S, T> machine, WatchSpec<E, S, T, ?, ?, ?> watch, E entity,
            Optional<Observed> observed, boolean reaction, S state) {
        try {
            return firstMatch(machine, watch, entity);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw unresolved(machine, entity, observed, reaction, Optional.of(state), "the watch threw ", e);
        }
    }

    /**
     * Ends a reevaluation that {@code thrown} ended before an edge ran, in RESOLVE with nothing written: attaches its
     * own flow to a failure of the call when the machine records its trail, logs its failed line, unless it is an
     * Error, and tells the observers. An Error that the trail's clock or the failed line throws is thrown in its place,
     * once the observers heard of it.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> void unresolvedEnd(
            DefaultStateMachine<E, S, T> machine, E entity, Optional<Observed> observed, CallLog log,
            Throwable thrown) {
        if (machine.trail().isPresent() && thrown instanceof CallFailedException own) {
            List<FlowEntry> entries = new ArrayList<>(1);
            try {
                OutcomeFlows.endWith(entries, new CallFailure(CallPhase.RESOLVE, StatesWritten.NONE, own),
                        machine.trail().get());
            } catch (Error fatal) {
                RootCall.failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, fatal);
                throw fatal;
            }
            CallFailureFlows.attach(own, new FrozenFlow(Optional.of(entity), Map.of(), entries));
        }
        if (log.debug() && !(thrown instanceof Error)) {
            try {
                log.failed(new CallFailure(CallPhase.RESOLVE, StatesWritten.NONE, thrown));
            } catch (Error fatal) {
                RootCall.failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, fatal);
                throw fatal;
            }
        }
        RootCall.failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, thrown);
    }

    /**
     * A reevaluation that could not read its entity's state or evaluate its watch: an abort in RESOLVE that carries
     * the failure, unwrapped from CompletionException and ExecutionException. Its caller has already interrupted the
     * thread again for an InterruptedException that the getter or the watch threw.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> TransitionAbortedException unresolved(
            DefaultStateMachine<E, S, T> machine, E entity, Optional<Observed> observed, boolean reaction,
            Optional<Enum<?>> from, String what, Throwable error) {
        Throwable cause = Unwrap.unwrap(error);
        AbortDetails details = new AbortDetails(machine.key(), entity, from, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of());
        return new TransitionAbortedException(RootCall.callOf(machine, entity, observed, reaction), CallPhase.RESOLVE,
                details, what + Texts.shown(cause), cause);
    }

    /**
     * Rules in order; the first one wins. The view is fresh: outside any transaction it reads the getters. A peersOf
     * that returns null instead of a collection is refused with a NullPointerException that says so, before any
     * condition runs.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> Optional<T>
            firstMatch(DefaultStateMachine<E, S, T> machine, WatchSpec<E, S, T, P, Q, U> watch, E entity) {
        Collection<? extends P> peers = watch.peersOf().apply(entity);
        if (peers == null) {
            throw new NullPointerException("the peersOf of " + machine.key() + "." + watch.state().name()
                    + " returned null");
        }
        PeerView<P, Q, U> view = DefaultPeerView.committed(machine.registry().machineFor(watch.peer()));
        for (WatchRule<E, S, T, P, Q, U> rule : watch.rules()) {
            if (rule.condition().condition().test(entity, peers, view)) {
                return Optional.of(rule.transition());
            }
        }
        return Optional.empty();
    }
}
