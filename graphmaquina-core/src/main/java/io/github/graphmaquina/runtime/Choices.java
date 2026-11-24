package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StatesWritten;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The choice of a machine, which {@link DefaultStateMachine#choose} runs: one root call that tries the caller's
 * candidates in order, each as the root step of an execute of it, and runs the first one admitted, as
 * {@link Attempts#firstAdmitted} does; and its check, which {@link DefaultStateMachine#chooseEligibility} runs: the
 * eligibility check of each candidate in turn, as {@link Checks} runs one, until one is eligible.
 */
final class Choices {

    private Choices() { }

    /**
     * The choice of the first admitted of {@code candidates} for {@code entity}, with {@code input} as the operation
     * input its guards and actions read, empty when it carries none. It is refused before it starts, with nothing
     * logged: a list that {@link #accepted} refuses, then a call where no call may start with
     * ReentrantTransitionException. Then it opens its log, tells the observers that a call that requests
     * {@link CallRequest#CHOOSE} and no identifier starts, and logs its start line,
     * {@code start <machine> choose <T>,<T>...}, the candidates in order: an Error that line throws ends the call in
     * RESOLVE with nothing written, once the observers heard of it. Then it runs as a root call of the application,
     * which no watch or automatic level holds and no chain continues, whose body is {@link Attempts#firstAdmitted}.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Outcome<E, S, T> choose(
            DefaultStateMachine<E, S, T> machine, E entity, List<T> candidates, Optional<Object> input) {
        List<T> tried = accepted(machine.key(), candidates);
        Reentrancy.check(machine.key(), entity);
        CallLog log = CallLog.open(Optional.empty());
        Optional<Observed> observed = RootCall.observe(machine, entity, CallRequest.CHOOSE, Optional.empty(),
                CallOrigin.APPLICATION, log);
        if (log.debug()) {
            try {
                log.start(machine.key(), "choose " + names(tried));
            } catch (Error fatal) {
                RootCall.failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, fatal);
                throw fatal;
            }
        }
        return RootCall.run(machine, entity, CallRequest.CHOOSE, Optional.empty(), CallOrigin.APPLICATION, observed,
                log, (Transaction tx) -> Attempts.firstAdmitted(tx, machine, entity, tried, input),
                new ChainLevels(0, 0), Optional.empty()).outcome();
    }

    /**
     * Which of {@code candidates} a choice would run for {@code entity}, with {@code input} as the operation input its
     * guards read, empty when it carries none. A list that {@link #accepted} refuses is refused before any check
     * starts, with nothing logged. Then each candidate in order gets the check {@link Checks#check} runs, which refuses
     * a check that may not start on this thread before it starts, and logs as such a check does; from a synchronous
     * guard, each is a check nested in that guard's call or check. The first eligible one answers
     * {@link ChoiceEligibility.Chosen}, with the refusals before it; otherwise the answer is
     * {@link ChoiceEligibility.NoneAdmitted}, with every refusal, from the state the first check read. What a check
     * throws propagates, and no later candidate is checked. The checks are one request, as {@link RequestScope} says:
     * every candidate's guards see one instant.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> ChoiceEligibility<E, S, T> check(
            DefaultStateMachine<E, S, T> machine, E entity, List<T> candidates, Optional<Object> input) {
        List<T> checked = accepted(machine.key(), candidates);
        List<Eligibility.Ineligible<E, S, T>> rejected = new ArrayList<>(checked.size());
        Optional<RequestScope> request = Optional.of(new RequestScope(machine.clock()));
        for (T candidate : checked) {
            Eligibility<E, S, T> verdict = Checks.check(machine, entity, candidate, input, request);
            if (verdict instanceof Eligibility.Ineligible<E, S, T> refusal) {
                rejected.add(refusal);
            } else {
                return new ChoiceEligibility.Chosen<>(entity, verdict.from(), candidate, rejected);
            }
        }
        return new ChoiceEligibility.NoneAdmitted<>(entity, rejected.get(0).from(), rejected);
    }

    /**
     * A copy of {@code candidates}, read once. It refuses, in this order, a null element with NullPointerException, an
     * empty list, then, element by element, an identifier of another catalogue than {@code key}'s, which only raw
     * types pass, and one listed before, each with IllegalArgumentException.
     */
    private static <T extends Enum<T>> List<T> accepted(MachineKey<?, ?, T> key, List<T> candidates) {
        List<T> copy = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            copy.add(Objects.requireNonNull(candidate, "candidates contains a null element"));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        EnumSet<T> seen = EnumSet.noneOf(key.transitions());
        for (T candidate : copy) {
            Transaction.requireTransition(key, candidate);
            if (!seen.add(candidate)) {
                throw new IllegalArgumentException("candidates lists " + candidate.name() + " twice");
            }
        }
        return List.copyOf(copy);
    }

    /** The names of {@code candidates}, in order, joined by commas. */
    private static String names(List<? extends Enum<?>> candidates) {
        StringJoiner names = new StringJoiner(",");
        for (Enum<?> candidate : candidates) {
            names.add(candidate.name());
        }
        return names.toString();
    }
}
