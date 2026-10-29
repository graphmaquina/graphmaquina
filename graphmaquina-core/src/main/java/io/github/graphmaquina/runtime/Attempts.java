package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AutomaticChainExceededException;
import io.github.graphmaquina.internal.Interrupts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An automatic attempt on an entity of a machine, which {@code advance} makes, and each step of an automatic chain as
 * {@link AutomaticChains} runs it: under the reentrancy marker, the state read and the automatic edges of that state,
 * its candidates; then, when there are any, a root call that tries them in order, each as the root step of an execute
 * of its identifier, and runs the first one admitted.
 */
final class Attempts {

    private Attempts() { }

    /**
     * One attempt on {@code entity}. It refuses a call made where no call may start before it starts, then logs its
     * start line, and reads the state and its candidates under the reentrancy marker, as a reevaluation reads its
     * state and its watch: a getter that throws or answers null, or a graph whose automaticFrom throws, ends the call
     * in RESOLVE, as {@link Reevaluation#unresolvedEnd} says. A state without automatic edges ends it with an Ignored
     * of NO_AUTOMATIC_TRANSITION, which ran no step. Otherwise the candidates run as a root call, as
     * {@link #firstAdmitted} says. {@code origin} says who made the call and {@code trigger} is the id the start line
     * names, empty for an advance the application made. {@code levels} is where the attempt stands in the watch and
     * automatic chains: an attempt whose automatic level is above maxAutomaticChain still tries its candidates, and the
     * one admitted throws an AutomaticChainExceededException once its guards passed, as {@link #exceeded} says.
     * {@code continuing} is the entity of the chain this attempt is a step of, which the attempt's own commit chains no
     * further, and empty for an advance the application made. The attempt hands back what its commit says of its
     * entity, as {@link #attemptOf} reads it.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Attempt<E, S, T> attempt(DefaultStateMachine<E, S, T> machine,
                                                                             E entity, CallOrigin origin,
                                                                             Optional<Long> trigger,
                                                                             ChainLevels levels,
                                                                             Optional<EntityRef> continuing) {
        Reentrancy.check(machine.key(), entity);
        CallLog log = CallLog.open(trigger);
        Optional<Observed> observed = RootCall.observe(machine, entity, CallRequest.ADVANCE, Optional.empty(), origin,
                log);
        S state;
        List<TransitionSpec<E, S, T>> candidates;
        try {
            if (log.debug()) {
                log.start(machine.key(), "advance");
            }
            boolean[] marker = Reentrancy.enter(machine.key(), entity);
            try {
                state = Reevaluation.stateOf(machine, entity, observed, CallRequest.ADVANCE, origin);
                candidates = candidatesOf(machine, entity, observed, origin, state);
            } finally {
                marker[0] = false;
            }
        } catch (Throwable thrown) {
            Reevaluation.unresolvedEnd(machine, entity, observed, log, thrown);
            throw thrown;
        }
        if (candidates.isEmpty()) {
            Outcome<E, S, T> ignored = RootCall.ignored(observed, log,
                    new Outcome.Ignored<>(entity, state, IgnoreReason.NO_AUTOMATIC_TRANSITION));
            return new Attempt<>(ignored, List.of(), false, state);
        }
        boolean limited = levels.automatic() > machine.registry().maxAutomaticChain();
        Committed<E, S, T> committed = RootCall.run(machine, entity, CallRequest.ADVANCE, Optional.empty(), origin,
                observed, log, (Transaction tx) -> {
                    if (limited) {
                        tx.refuseRootActionWith(() -> exceeded(tx, machine));
                    }
                    return firstAdmitted(tx, machine, entity, state, candidates);
                }, levels, continuing);
        return attemptOf(machine, entity, state, committed);
    }

    /**
     * The automatic edges of {@code state}, the attempt's candidates, as the graph lists them; a graph whose
     * automaticFrom throws anything but an Error aborts the attempt in RESOLVE, as a state getter that throws does,
     * with what it threw as the cause. An Error propagates.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> List<TransitionSpec<E, S, T>> candidatesOf(
            DefaultStateMachine<E, S, T> machine, E entity, Optional<Observed> observed, CallOrigin origin, S state) {
        try {
            return machine.definition().automaticFrom(state);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw Reevaluation.unresolved(machine, entity, observed, CallRequest.ADVANCE, origin, Optional.of(state),
                    "reading the automatic edges threw ", e);
        }
    }

    /**
     * What {@code committed} says of {@code entity}: the entry its commit summary lists for it, found by the very
     * instance, which a commit registers once, says whether it stops the chain and the state it leaves the entity in.
     * An attempt that committed nothing lists none: it stops no chain, and its state is {@code read}, the one it read.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Attempt<E, S, T> attemptOf(
            DefaultStateMachine<E, S, T> machine, E entity, S read, Committed<E, S, T> committed) {
        for (ChainStart start : committed.summary().chainStarts()) {
            if (start.ref().instance() == entity && start.ref().key().equals(machine.key())) {
                return new Attempt<>(committed.outcome(), committed.movedBelow(), start.stopsChain(),
                        machine.key().states().cast(start.state()));
            }
        }
        return new Attempt<>(committed.outcome(), committed.movedBelow(), false, read);
    }

    /**
     * The abort of an attempt past maxAutomaticChain, which its admitted candidate throws once its guards passed,
     * before the call enters ACTION: an abort of the attempt's own call, in the phase the call is in, GUARD when the
     * candidate has guards and RESOLVE otherwise, that describes the root step in flight, the candidate's edge, with
     * no action. The candidate's action never runs, and nothing is written.
     */
    private static AutomaticChainExceededException exceeded(Transaction tx, DefaultStateMachine<?, ?, ?> machine) {
        InFlight root = tx.uow().rootFrame();
        AbortDetails details = tx.details(machine, root.ref(), root.spec().from(), Optional.of(root.transition()),
                Optional.of(root.spec().to()), Optional.empty());
        return new AutomaticChainExceededException(tx.call(), tx.phase(), details,
                machine.registry().maxAutomaticChain());
    }

    /**
     * The body of the attempt's root call: each candidate in turn, as the root step of an execute of its identifier,
     * until one is not rejected, whose result is the call's. A rejected step wrote nothing and ran no action, though
     * its binding check and its guards up to the rejecting one ran; it leaves the call in GUARD, so the call goes back
     * to RESOLVE before each candidate. A call that logs at TRACE logs each rejection after the lines of its step.
     * When every candidate was rejected, the result is an Ignored of NO_CANDIDATE_ADMITTED that lists each rejection
     * in order, with the state that candidate's step read; {@code from} is the state the attempt read.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> firstAdmitted(
            Transaction tx, DefaultStateMachine<E, S, T> machine, E entity, S from,
            List<TransitionSpec<E, S, T>> candidates) {
        List<Eligibility.Ineligible<?, S, T>> rejections = new ArrayList<>(candidates.size());
        for (TransitionSpec<E, S, T> candidate : candidates) {
            tx.enter(CallPhase.RESOLVE);
            StepResult<S, T> step = Steps.transition(tx, machine, entity, candidate.transition(), 0, Optional.empty());
            if (!(step instanceof StepResult.Rejected<S, T> rejected)) {
                return step;
            }
            if (tx.log().trace()) {
                tx.log().trace(0, refused(rejected));
            }
            rejections.add(new Eligibility.Ineligible<>(entity, rejected.from(), rejected.transition(),
                    rejected.reason(), rejected.kind(), rejected.guard()));
        }
        return new StepResult.Ignored<>(from, IgnoreReason.NO_CANDIDATE_ADMITTED, rejections);
    }

    /**
     * The text of the TRACE line of a rejected candidate:
     * {@code candidate <T> refused <kind>[ guard=<name>]: <reason>}, the reason as given.
     */
    private static String refused(StepResult.Rejected<?, ?> rejected) {
        return "candidate " + rejected.transition().name() + " refused " + rejected.kind().name()
                + (rejected.guard().isPresent() ? " guard=" + rejected.guard().get() : "") + ": " + rejected.reason();
    }
}
